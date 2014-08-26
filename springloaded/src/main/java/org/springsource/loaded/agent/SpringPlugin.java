/*
 * Copyright 2010-2012 VMware and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springsource.loaded.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.springsource.loaded.GlobalConfiguration;
import org.springsource.loaded.LoadtimeInstrumentationPlugin;
import org.springsource.loaded.ReloadEventProcessorPlugin;


/**
 * First stab at the Spring plugin for Spring-Loaded. Notes...<br>
 * <ul>
 * <li>On reload, removes the Class entry in
 * org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter.methodResolverCache. This enables us to add/changes
 * request mappings in controllers.
 * <li>That was for Roo, if we create a simple spring template project and run it, this doesn't work. It seems we need to redrive
 * detectHandlers() on the DefaultAnnotationHandlerMapping type which will rediscover the URL mappings and add them into the handler
 * list. We don't clear old ones out (yet) but the old mappings appear not to work anyway.
 * </ul>
 * 
 * @author Andy Clement
 * @since 0.5.0
 */
public class SpringPlugin implements LoadtimeInstrumentationPlugin, ReloadEventProcessorPlugin {

	private static final String THIS_CLASS = "org/springsource/loaded/agent/SpringPlugin";

	private static Logger log = Logger.getLogger(SpringPlugin.class.getName());

	private static boolean debug = true;
	
	// TODO [gc] what about GC here - how do we know when they are finished with?
	public static List<Object> instancesOf_AnnotationMethodHandlerAdapter = new ArrayList<Object>();
	public static List<Object> instancesOf_DefaultAnnotationHandlerMapping = new ArrayList<Object>();
	public static List<Object> instancesOf_RequestMappingHandlerMapping = new ArrayList<Object>();
	public static List<Object> instancesOf_LocalVariableTableParameterNameDiscoverer = null;

	ClassLoader springLoader = null;
	
	public static boolean support305 = true;

	private Field classCacheField;
	private Field strongClassCacheField;
	private Field softClassCacheField;

	private Field field_parameterNamesCache; // From LocalVariableTableParameterNameDiscoverer

	private boolean cachedIntrospectionResultsClassLoaded = false;
	
	private Class<?> cachedIntrospectionResultsClass = null;

	public boolean accept(String slashedTypeName, ClassLoader classLoader, ProtectionDomain protectionDomain, byte[] bytes) {
		// TODO take classloader into account?
		if (slashedTypeName == null) {
			return false;
		}
		if (slashedTypeName.equals("org/springframework/core/LocalVariableTableParameterNameDiscoverer")) {
			return true;
		}
		// Just interested in whether this type got loaded
		if (slashedTypeName.equals("org/springframework/beans/CachedIntrospectionResults")) {
			cachedIntrospectionResultsClassLoaded = true;
		}
		return slashedTypeName.equals("org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter") ||
				slashedTypeName.equals("org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping") || // 3.1
				(support305 && slashedTypeName
						.equals("org/springframework/web/servlet/mvc/annotation/DefaultAnnotationHandlerMapping"));
	}
	

	public byte[] modify(String slashedClassName, ClassLoader classLoader, byte[] bytes) {
		if (GlobalConfiguration.isRuntimeLogging && log.isLoggable(Level.INFO)) {
			log.info("loadtime modifying " + slashedClassName);
		}
		if (slashedClassName.equals("org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter")) {
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordAnnotationMethodHandlerAdapterInstance");
		} 
		else if (slashedClassName.equals("org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping")) {
			// springmvc spring 3.1 - doesnt work on 3.1 post M2 snapshots
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordRequestMappingHandlerMappingInstance");
		}
		else if (slashedClassName.equals("org/springframework/core/LocalVariableTableParameterNameDiscoverer")) {
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,"recordLocalVariableTableParameterNameDiscoverer");
		}
		else { // "org/springframework/web/servlet/mvc/annotation/DefaultAnnotationHandlerMapping"
			// springmvc spring 3.0
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordDefaultAnnotationHandlerMappingInstance");
		}
	}

	// called by the modified code
	public static void recordAnnotationMethodHandlerAdapterInstance(Object obj) {
		instancesOf_AnnotationMethodHandlerAdapter.add(obj);
	}

	public static void recordRequestMappingHandlerMappingInstance(Object obj) {
		instancesOf_RequestMappingHandlerMapping.add(obj);
	}

	public static void recordLocalVariableTableParameterNameDiscoverer(Object obj) {
		if (instancesOf_LocalVariableTableParameterNameDiscoverer == null) {
			instancesOf_LocalVariableTableParameterNameDiscoverer = new ArrayList<Object>();
		}
		instancesOf_LocalVariableTableParameterNameDiscoverer.add(obj);
	}

	static {
		try {
			String debugString = System.getProperty("springloaded.plugins.spring.debug","false");
			debug = Boolean.valueOf(debugString);
		} catch (Exception e) {
			// likely security exception
		}
	}

	// called by the modified code
	public static void recordDefaultAnnotationHandlerMappingInstance(Object obj) {
		if (debug) {
			System.out.println("Recording new instance of DefaultAnnotationHandlerMappingInstance");
		}
		instancesOf_DefaultAnnotationHandlerMapping.add(obj);
	}

	public void reloadEvent(String typename, Class<?> clazz, String versionsuffix) {
		removeClazzFromMethodResolverCache(clazz);
		clearCachedIntrospectionResults(clazz);
		reinvokeDetectHandlers(); // Spring 3.0
		reinvokeInitHandlerMethods(); // Spring 3.1
		clearLocalVariableTableParameterNameDiscovererCache(clazz);
	}

	/**
	 * The Spring class LocalVariableTableParameterNameDiscoverer holds a cache of parameter names discovered for members within
	 * classes and needs clearing if the class changes.
	 * @param clazz the class being reloaded, which may exist in a parameter name discoverer cache
	 */
	private void clearLocalVariableTableParameterNameDiscovererCache(Class<?> clazz) {
		if (instancesOf_LocalVariableTableParameterNameDiscoverer == null) {
			return;
		}
		if (debug) {
			System.out.println("ParameterNamesCache: Clearing parameter name discoverer caches");
		}
		if (field_parameterNamesCache == null) {
			try {
				field_parameterNamesCache = instancesOf_LocalVariableTableParameterNameDiscoverer.get(0).getClass().getDeclaredField("parameterNamesCache");
			} catch (NoSuchFieldException nsfe) {
				log.log(Level.SEVERE, "Unexpectedly cannot find parameterNamesCache field on LocalVariableTableParameterNameDiscoverer class");
			}
		}
		for (Object instance: instancesOf_LocalVariableTableParameterNameDiscoverer) {
			field_parameterNamesCache.setAccessible(true);
			try {
				Map<?,?> parameterNamesCache = (Map<?,?>)field_parameterNamesCache.get(instance);
				Object o = parameterNamesCache.remove(clazz);
				if (debug) {
					System.out.println("ParameterNamesCache: Removed "+clazz.getName()+" from cache?"+(o!=null));
				}
			} catch (IllegalAccessException e) {
				log.log(Level.SEVERE, "Unexpected IllegalAccessException trying to access parameterNamesCache field on LocalVariableTableParameterNameDiscoverer class");
			}
		}
	}

	private void removeClazzFromMethodResolverCache(Class<?> clazz) {
		for (Object o : instancesOf_AnnotationMethodHandlerAdapter) {
			try {
				Field f = o.getClass().getDeclaredField("methodResolverCache");
				f.setAccessible(true);
				Map<?, ?> map = (Map<?, ?>) f.get(o);
				Method removeMethod = Map.class.getDeclaredMethod("remove", Object.class);
				Object ret = removeMethod.invoke(map, clazz);
				if (GlobalConfiguration.debugplugins) {
					System.err.println("SpringPlugin: clearing methodResolverCache for " + clazz.getName());
				}
				if (GlobalConfiguration.isRuntimeLogging && log.isLoggable(Level.INFO)) {
					log.info("cleared a cache entry? " + (ret != null));
				}
			} catch (Exception e) {
				log.log(Level.SEVERE, "Unexpected problem accessing methodResolverCache on " + o, e);
			}
		}
	}

	private void clearCachedIntrospectionResults(Class<?> clazz) {
		if (cachedIntrospectionResultsClassLoaded) {
			try {
				// TODO not a fan of classloading like this
				if (cachedIntrospectionResultsClass == null) {
					// TODO what about two apps using reloading and diff versions of spring?
					cachedIntrospectionResultsClass = clazz.getClassLoader().loadClass(
							"org.springframework.beans.CachedIntrospectionResults");
				}

				if (classCacheField == null && strongClassCacheField == null) {
					try {
						classCacheField = cachedIntrospectionResultsClass.getDeclaredField("classCache");
					} catch(NoSuchFieldException e) {
						strongClassCacheField = cachedIntrospectionResultsClass.getDeclaredField("strongClassCache");
						softClassCacheField = cachedIntrospectionResultsClass.getDeclaredField("softClassCache");
					}

				}
				if(classCacheField != null) {
					classCacheField.setAccessible(true);
					Map m = (Map) classCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.classCache for " + clazz.getName() + " removed=" + o);
					}
				}
				if(strongClassCacheField != null) {
					strongClassCacheField.setAccessible(true);
					Map m = (Map) strongClassCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.strongClassCache for " + clazz.getName() + " removed=" + o);
					}
				}
				if(softClassCacheField != null) {
					softClassCacheField.setAccessible(true);
					Map m = (Map) softClassCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.softClassCache for " + clazz.getName() + " removed=" + o);
					}
				}

			} catch (Exception e) {
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	private void reinvokeDetectHandlers() {
		// want to call detectHandlers on the DefaultAnnotationHandlerMapping type
		// protected void detectHandlers() throws BeansException {  is defined on  AbstractDetectingUrlHandlerMapping
		for (Object o : instancesOf_DefaultAnnotationHandlerMapping) {
			if (debug) {
				System.out.println("Invoking detectHandlers on instance of DefaultAnnotationHandlerMappingInstance");
			}
			try {
				Class<?> clazz_AbstractDetectingUrlHandlerMapping = o.getClass().getSuperclass();
				Method method_detectHandlers = clazz_AbstractDetectingUrlHandlerMapping.getDeclaredMethod("detectHandlers");
				method_detectHandlers.setAccessible(true);
				method_detectHandlers.invoke(o);
			} catch (Exception e) {
				// if debugging then print it
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	@SuppressWarnings("rawtypes")
	private void reinvokeInitHandlerMethods() {
		//		org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping (super AbstractHandlerMethodMapping) - call protected void initHandlerMethods() on it.

		for (Object o : instancesOf_RequestMappingHandlerMapping) {
			if (debug) {
				System.out.println("Invoking initHandlerMethods on instance of RequestMappingHandlerMapping");
			}
			try {
				Class<?> clazz_AbstractHandlerMethodMapping = o.getClass().getSuperclass().getSuperclass();

				// private final Map<T, HandlerMethod> handlerMethods = new LinkedHashMap<T, HandlerMethod>();
				Field field_handlerMethods = clazz_AbstractHandlerMethodMapping.getDeclaredField("handlerMethods");
				field_handlerMethods.setAccessible(true);
				Map m = (Map) field_handlerMethods.get(o);
				m.clear();

				Field field_urlMap = clazz_AbstractHandlerMethodMapping.getDeclaredField("urlMap");
				field_urlMap.setAccessible(true);
				m = (Map) field_urlMap.get(o);
				m.clear();

				Method method_initHandlerMethods = clazz_AbstractHandlerMethodMapping.getDeclaredMethod("initHandlerMethods");
				method_initHandlerMethods.setAccessible(true);
				method_initHandlerMethods.invoke(o);
			} catch (NoSuchFieldException nsfe) {
				if (debug) {
					if (nsfe.getMessage().equals("handlerMethods")) {
						System.out.println("problem resetting request mapping handlers - unable to find field 'handlerMethods' on type 'AbstractHandlerMethodMapping' - you probably are not on Spring 3.1");
					}
					else {
						System.out.println("problem resetting request mapping handlers - NoSuchFieldException: "+nsfe.getMessage());
					}
				}
			} catch (Exception e) {
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	public boolean shouldRerunStaticInitializer(String typename, Class<?> clazz, String encodedTimestamp) {
		return false;
	}

	/**
	 * Modify the supplied bytes such that constructors are intercepted and will invoke the specified class/method so that the
	 * instances can be tracked.
	 * 
	 * @return modified bytes for the class
	 */
	private byte[] bytesWithInstanceCreationCaptured(byte[] bytes, String classToCall, String methodToCall) {
		ClassReader cr = new ClassReader(bytes);
		ClassVisitingConstructorAppender ca = new ClassVisitingConstructorAppender(classToCall, methodToCall);
		cr.accept(ca, 0);
		byte[] newbytes = ca.getBytes();
		return newbytes;
	}

}
