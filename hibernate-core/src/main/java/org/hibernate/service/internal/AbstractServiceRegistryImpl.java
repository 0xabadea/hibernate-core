/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.service.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.service.BootstrapServiceRegistry;
import org.hibernate.service.Service;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.service.UnknownServiceException;
import org.hibernate.service.jmx.spi.JmxService;
import org.hibernate.service.spi.InjectService;
import org.hibernate.service.spi.Manageable;
import org.hibernate.service.spi.ServiceBinding;
import org.hibernate.service.spi.ServiceException;
import org.hibernate.service.spi.ServiceInitiator;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.service.spi.Startable;
import org.hibernate.service.spi.Stoppable;

/**
 * @author Steve Ebersole
 */
public abstract class AbstractServiceRegistryImpl implements ServiceRegistryImplementor, ServiceBinding.OwningRegistry {
	private static final CoreMessageLogger LOG = Logger.getMessageLogger( CoreMessageLogger.class, AbstractServiceRegistryImpl.class.getName() );

	private final ServiceRegistryImplementor parent;

	private ConcurrentHashMap<Class,ServiceBinding> serviceBindingMap;
	// IMPL NOTE : the list used for ordered destruction.  Cannot used map above because we need to
	// iterate it in reverse order which is only available through ListIterator
	private List<Service> serviceList = new ArrayList<Service>();

	@SuppressWarnings( {"UnusedDeclaration"})
	protected AbstractServiceRegistryImpl() {
		this( (ServiceRegistryImplementor) null );
	}

	protected AbstractServiceRegistryImpl(ServiceRegistryImplementor parent) {
		this.parent = parent;
		prepare();

	}

	private void prepare() {
		// assume 20 services for initial sizing
		this.serviceBindingMap = CollectionHelper.concurrentMap( 20 );
		this.serviceList = CollectionHelper.arrayList( 20 );
	}

	public AbstractServiceRegistryImpl(BootstrapServiceRegistry bootstrapServiceRegistry) {
		if ( ! ServiceRegistryImplementor.class.isInstance( bootstrapServiceRegistry ) ) {
			throw new IllegalArgumentException( "Boot-strap registry was not " );
		}
		this.parent = (ServiceRegistryImplementor) bootstrapServiceRegistry;
		prepare();
	}

	@SuppressWarnings({ "unchecked" })
	protected <R extends Service> void createServiceBinding(ServiceInitiator<R> initiator) {
		serviceBindingMap.put( initiator.getServiceInitiated(), new ServiceBinding( this, initiator ) );
	}

	protected <R extends Service> void createServiceBinding(ProvidedService<R> providedService) {
		ServiceBinding<R> binding = locateServiceBinding( providedService.getServiceRole(), false );
		if ( binding == null ) {
			binding = new ServiceBinding<R>( this, providedService.getServiceRole(), providedService.getService() );
			serviceBindingMap.put( providedService.getServiceRole(), binding );
		}
		registerService( binding, providedService.getService() );
	}

	@Override
	@SuppressWarnings( {"unchecked"})
	public ServiceRegistry getParentServiceRegistry() {
		return parent;
	}

	@Override
	@SuppressWarnings({ "unchecked" })
	public <R extends Service> ServiceBinding<R> locateServiceBinding(Class<R> serviceRole) {
		return locateServiceBinding( serviceRole, true );
	}

	@SuppressWarnings({ "unchecked" })
	protected <R extends Service> ServiceBinding<R> locateServiceBinding(Class<R> serviceRole, boolean checkParent) {
		ServiceBinding<R> serviceBinding = serviceBindingMap.get( serviceRole );
		if ( serviceBinding == null && checkParent && parent != null ) {
			// look in parent
			serviceBinding = parent.locateServiceBinding( serviceRole );
		}
		return serviceBinding;
	}

	@Override
	public <R extends Service> R getService(Class<R> serviceRole) {
		final ServiceBinding<R> serviceBinding = locateServiceBinding( serviceRole );
		if ( serviceBinding == null ) {
			throw new UnknownServiceException( serviceRole );
		}

		R service = serviceBinding.getService();
		if ( service == null ) {
			service = initializeService( serviceBinding );
		}

		return service;
	}

	protected <R extends Service> void registerService(ServiceBinding<R> serviceBinding, R service) {
		R priorServiceInstance = serviceBinding.getService();
		serviceBinding.setService( service );
		if ( priorServiceInstance != null ) {
			serviceList.remove( priorServiceInstance );
		}
		serviceList.add( service );
	}

	private <R extends Service> R initializeService(ServiceBinding<R> serviceBinding) {
		if ( LOG.isTraceEnabled() ) {
			LOG.trace( "Initializing service [role=" + serviceBinding.getServiceRole().getName() + "]" );
		}

		// PHASE 1 : create service
		R service = createService( serviceBinding );
		if ( service == null ) {
			return null;
		}

		// PHASE 2 : configure service (***potentially recursive***)
		configureService( service );

		// PHASE 3 : Start service
		startService( serviceBinding );

		return service;
	}

	@SuppressWarnings( {"unchecked"})
	protected <R extends Service> R createService(ServiceBinding<R> serviceBinding) {
		final ServiceInitiator<R> serviceInitiator = serviceBinding.getServiceInitiator();
		if ( serviceInitiator == null ) {
			// this condition should never ever occur
			throw new UnknownServiceException( serviceBinding.getServiceRole() );
		}

		try {
			R service = serviceBinding.getServiceRegistry().initiateService( serviceInitiator );
			// IMPL NOTE : the register call here is important to avoid potential stack overflow issues
			//		from recursive calls through #configureService
			registerService( serviceBinding, service );
			return service;
		}
		catch ( ServiceException e ) {
			throw e;
		}
		catch ( Exception e ) {
			throw new ServiceException( "Unable to create requested service [" + serviceBinding.getServiceRole().getName() + "]", e );
		}
	}

	protected abstract <T extends Service> void configureService(T service);

	protected <T extends Service> void applyInjections(T service) {
		try {
			for ( Method method : service.getClass().getMethods() ) {
				InjectService injectService = method.getAnnotation( InjectService.class );
				if ( injectService == null ) {
					continue;
				}

				applyInjection( service, method, injectService );
			}
		}
		catch (NullPointerException e) {
            LOG.error("NPE injecting service deps : " + service.getClass().getName());
		}
	}

	@SuppressWarnings({ "unchecked" })
	private <T extends Service> void applyInjection(T service, Method injectionMethod, InjectService injectService) {
		if ( injectionMethod.getParameterTypes() == null || injectionMethod.getParameterTypes().length != 1 ) {
			throw new ServiceDependencyException(
					"Encountered @InjectService on method with unexpected number of parameters"
			);
		}

		Class dependentServiceRole = injectService.serviceRole();
		if ( dependentServiceRole == null || dependentServiceRole.equals( Void.class ) ) {
			dependentServiceRole = injectionMethod.getParameterTypes()[0];
		}

		// todo : because of the use of proxies, this is no longer returning null here...

		final Service dependantService = getService( dependentServiceRole );
		if ( dependantService == null ) {
			if ( injectService.required() ) {
				throw new ServiceDependencyException(
						"Dependency [" + dependentServiceRole + "] declared by service [" + service + "] not found"
				);
			}
		}
		else {
			try {
				injectionMethod.invoke( service, dependantService );
			}
			catch ( Exception e ) {
				throw new ServiceDependencyException( "Cannot inject dependency service", e );
			}
		}
	}

	@SuppressWarnings({ "unchecked" })
	protected <R extends Service> void startService(ServiceBinding<R> serviceBinding) {
		if ( Startable.class.isInstance( serviceBinding.getService() ) ) {
			( (Startable) serviceBinding.getService() ).start();
		}

		if ( Manageable.class.isInstance( serviceBinding.getService() ) ) {
			getService( JmxService.class ).registerService(
					(Manageable) serviceBinding.getService(),
					serviceBinding.getServiceRole()
			);
		}
	}

	public void destroy() {
		ListIterator<Service> serviceIterator = serviceList.listIterator( serviceList.size() );
		while ( serviceIterator.hasPrevious() ) {
			final Service service = serviceIterator.previous();
			if ( Stoppable.class.isInstance( service ) ) {
				try {
					( (Stoppable) service ).stop();
				}
				catch ( Exception e ) {
                    LOG.unableToStopService(service.getClass(), e.toString());
				}
			}
		}
		serviceList.clear();
		serviceList = null;
		serviceBindingMap.clear();
		serviceBindingMap = null;
	}

}
