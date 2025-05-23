/*
   Copyright 2019-2021 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.frankframework.lifecycle;

import static java.util.Objects.requireNonNull;

import java.io.Closeable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cxf.bus.spring.SpringBus;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ResourceUtils;

import org.frankframework.components.ComponentLoader;
import org.frankframework.util.AppConstants;
import org.frankframework.util.LogUtil;
import org.frankframework.util.SpringUtils;

/**
 * Creates and maintains the (Spring) Application Context. If the context is loaded through a {@link FrankApplicationInitializer servlet}
 * it will register the servlet in the context. When the Application Context is created or destroyed it will also create/destroy the servlet.
 * This ensures that the correct {@link SpringBus bus} will be used in which CXF will register it's endpoints and dispatchers.
 * <p>
 * <br/><br/>
 * <p>
 * It is important that the Application Context is created before the {@link FrankApplicationInitializer servlet} initializes.
 * Otherwise the servlet will register under the wrong {@link SpringBus bus}!
 * <p>
 * <br/><br/>
 * <p>
 * It is possible to retrieve the Application Context through the Spring WebApplicationContextUtils class
 *
 * @see org.springframework.web.context.support.WebApplicationContextUtils#getWebApplicationContext
 */
public class IbisApplicationContext implements Closeable {

	public enum BootState {
		FIRST_START, STARTING, STARTED, STOPPING, STOPPED, ERROR
	}

	private AbstractApplicationContext applicationContext;
	private ApplicationContext parentContext = null;

	private static final Logger LOG = LogUtil.getLogger(IbisApplicationContext.class);
	protected static final AppConstants APP_CONSTANTS = AppConstants.getInstance();
	private static final Logger APPLICATION_LOG = LogUtil.getLogger("APPLICATION");
	private BootState state = BootState.FIRST_START;


	public void setParentContext(ApplicationContext parentContext) {
		this.parentContext = parentContext;
	}

	/**
	 * Create Spring Bean factory.
	 * <p>
	 * Create the Spring Bean Factory using the default <code>springContext</code>,
	 * if not <code>null</code>.
	 *
	 * @throws BeansException If the Factory can not be created.
	 */
	protected void createApplicationContext() throws BeansException {
		APPLICATION_LOG.debug("Creating IbisApplicationContext");
		if (state != BootState.FIRST_START) {
			state = BootState.STARTING;
		}

		long start = System.currentTimeMillis();

		try {
			applicationContext = createClassPathApplicationContext();
			if (parentContext != null) {
				LOG.info("found Spring rootContext [{}]", parentContext);
				applicationContext.setParent(parentContext);
			}
			applicationContext.refresh();
		} catch (BeansException be) {
			state = BootState.ERROR;
			APPLICATION_LOG.fatal("Unable to initialize IbisApplicationContext", be);
			throw be;
		}

		APPLICATION_LOG.info("Created IbisApplicationContext [{}] in {} ms", applicationContext::getId, () -> (System.currentTimeMillis() - start));
		state = BootState.STARTED;
	}

	/**
	 * Loads springUnmanagedDeployment, SpringApplicationContext and files specified by the SPRING.CONFIG.LOCATIONS
	 * property in AppConstants.properties
	 *
	 * @param classLoader to use in order to find and validate the Spring Configuration files
	 * @return A String array containing all files to use.
	 */
	protected String[] getSpringConfigurationFiles(ClassLoader classLoader) {
		List<String> springConfigurationFiles = new ArrayList<>();
		if (parentContext == null) { // When not running in a web container, populate top-level beans so they can be found throughout this/sub-contexts.
			springConfigurationFiles.add(SpringContextScope.STANDALONE.getContextFile());
		}
		springConfigurationFiles.add(SpringContextScope.APPLICATION.getContextFile());
		String configLocations = AppConstants.getInstance().getProperty("SPRING.CONFIG.LOCATIONS");
		springConfigurationFiles.addAll(splitIntoConfigFiles(classLoader, configLocations));

		List<String> additionalSpringConfigurationFiles = ComponentLoader.findAllModules().stream()
			.flatMap(module -> module.getSpringConfigurationFiles().stream())
			.filter(filename -> isSpringConfigFileOnClasspath(classLoader, filename))
			.map(this::addClasspathPrefix)
			.toList();
		springConfigurationFiles.addAll(additionalSpringConfigurationFiles);

		LOG.info("loading Spring configuration files {}", springConfigurationFiles);
		return springConfigurationFiles.toArray(new String[springConfigurationFiles.size()]);
	}

	private List<String> splitIntoConfigFiles(ClassLoader classLoader, String fileList) {
		return Arrays
			.stream(fileList.split(","))
			.filter(filename -> isSpringConfigFileOnClasspath(classLoader, filename))
			.map(this::addClasspathPrefix)
			.toList();
	}

	private boolean isSpringConfigFileOnClasspath(ClassLoader classLoader, String filename) {
		URL fileURL = classLoader.getResource(filename);
		if (fileURL == null) {
			LOG.error("unable to locate Spring configuration file [{}]", filename);
		}
		return fileURL != null;
	}

	private String addClasspathPrefix(String filename) {
		if (filename.contains(":")) {
			return filename;
		}
		return ResourceUtils.CLASSPATH_URL_PREFIX + "/" + filename;
	}

	/**
	 * Creates the Spring Application Context when ran from the command line.
	 *
	 * @throws BeansException when the Context fails to initialize
	 */
	private ClassPathXmlApplicationContext createClassPathApplicationContext() {
		ClassPathXmlApplicationContext classPathApplicationContext = new ClassPathXmlApplicationContext();

		MutablePropertySources propertySources = classPathApplicationContext.getEnvironment().getPropertySources();
		propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		propertySources.addFirst(new PropertiesPropertySource(SpringContextScope.APPLICATION.getFriendlyName(), APP_CONSTANTS));

		ClassLoader classLoader = classPathApplicationContext.getClassLoader();
		if (classLoader == null) throw new IllegalStateException("no ClassLoader found to initialize Spring from");
		classPathApplicationContext.setConfigLocations(getSpringConfigurationFiles(classLoader));

		String instanceName = APP_CONSTANTS.getProperty("instance.name");
		classPathApplicationContext.setId(requireNonNull(instanceName));
		classPathApplicationContext.setDisplayName("IbisApplicationContext [" + instanceName + "]");

		return classPathApplicationContext;
	}

	/**
	 * Destroys the Spring context
	 */
	@Override
	public void close() {
		if (applicationContext != null) {
			String oldContextName = applicationContext.getId();
			LOG.info("closing IbisApplicationContext [{}]", oldContextName);

			applicationContext.close();
			applicationContext = null;

			APPLICATION_LOG.info("Closed IbisApplicationContext [{}]", oldContextName);
		}
	}

	/**
	 * Create bean without passing the bean-class. Can be used when the compiler can statically determine the class from the variable to which the bean is assigned.
	 * Do not pass actual argument to reified, Java will auto-detect the class of the bean type.
	 */
	@SafeVarargs
	protected final <T> T getBean(String beanName, T... reified) {
		if (reified.length > 0) {
			throw new IllegalArgumentException("Do not pass any actual arguments to the reified parameter");
		}
		return applicationContext.getBean(beanName, SpringUtils.getClassOf(reified));
	}

	/**
	 * Returns the Spring XML Bean Factory If non exists yet it will create one.
	 * If initializing the context fails, it will return null.
	 * 
	 * Ideally this method should not be exposed....
	 *
	 * @return Spring XML Bean Factory or NULL
	 */
	public AbstractApplicationContext getApplicationContext() {
		if (applicationContext == null)
			createApplicationContext();

		return applicationContext;
	}

	public BootState getBootState() {
		return state;
	}
}
