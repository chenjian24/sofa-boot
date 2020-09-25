/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.runtime.spring;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.alipay.sofa.boot.util.BeanDefinitionUtil;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import com.alipay.sofa.boot.annotation.PlaceHolderAnnotationInvocationHandler.AnnotationWrapperBuilder;
import com.alipay.sofa.boot.annotation.PlaceHolderBinder;
import com.alipay.sofa.runtime.api.ServiceRuntimeException;
import com.alipay.sofa.runtime.api.annotation.SofaReference;
import com.alipay.sofa.runtime.api.annotation.SofaReferenceBinding;
import com.alipay.sofa.runtime.api.annotation.SofaService;
import com.alipay.sofa.runtime.api.annotation.SofaServiceBinding;
import com.alipay.sofa.runtime.api.binding.BindingType;
import com.alipay.sofa.runtime.log.SofaLogger;
import com.alipay.sofa.runtime.service.binding.JvmBinding;
import com.alipay.sofa.runtime.spi.binding.Binding;
import com.alipay.sofa.runtime.spi.component.SofaRuntimeContext;
import com.alipay.sofa.runtime.spi.service.BindingConverter;
import com.alipay.sofa.runtime.spi.service.BindingConverterContext;
import com.alipay.sofa.runtime.spi.service.BindingConverterFactory;
import com.alipay.sofa.runtime.spring.bean.SofaBeanNameGenerator;
import com.alipay.sofa.runtime.spring.factory.ReferenceFactoryBean;
import com.alipay.sofa.runtime.spring.factory.ServiceFactoryBean;
import com.alipay.sofa.runtime.spring.parser.AbstractContractDefinitionParser;
import com.alipay.sofa.runtime.spring.parser.ServiceDefinitionParser;

/**
 *	//==========================JUSTINWARE==========================================
 * 	//1、作为BeanFactoryPostProcessor子类，处理@SofaService注解
 * 	//==============================================================================
 * @author qilong.zql
 * @since 3.1.0
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceBeanFactoryPostProcessor implements BeanFactoryPostProcessor,
                                            ApplicationContextAware, EnvironmentAware {
    private final PlaceHolderBinder binder = new DefaultPlaceHolderBinder();
    //Spring上下文
    private ApplicationContext      applicationContext;
    private SofaRuntimeContext      sofaRuntimeContext;
    private BindingConverterFactory bindingConverterFactory;
    //环境遍历
    private Environment             environment;

    public ServiceBeanFactoryPostProcessor(SofaRuntimeContext sofaRuntimeContext,
                                           BindingConverterFactory bindingConverterFactory) {
        this.sofaRuntimeContext = sofaRuntimeContext;
        this.bindingConverterFactory = bindingConverterFactory;
    }
    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、遍历BeanDefinition,通过transformSofaBeanDefinition将普通bean处理为Sofa Bean
     * 	//==============================================================================
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Arrays.stream(beanFactory.getBeanDefinitionNames())
            .collect(Collectors.toMap(Function.identity(), beanFactory::getBeanDefinition))
            .forEach((key, value) -> transformSofaBeanDefinition(key, value, beanFactory));
    }

    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、定义SofaBeanDefinition
     * 	//==============================================================================
     * {@link ScannedGenericBeanDefinition}
     * {@link AnnotatedGenericBeanDefinition}
     * {@link GenericBeanDefinition}
     * {@link org.springframework.beans.factory.support.ChildBeanDefinition}
     * {@link org.springframework.beans.factory.support.RootBeanDefinition}
     */
    private void transformSofaBeanDefinition(String beanId, BeanDefinition beanDefinition,
                                             ConfigurableListableBeanFactory beanFactory) {
        if (BeanDefinitionUtil.isFromConfigurationSource(beanDefinition)) {
            //通过Configuration配置的Bean,这类的bean的注解是在方法上的。所以调用OnMethod方法
            generateSofaServiceDefinitionOnMethod(beanId, (AnnotatedBeanDefinition) beanDefinition,
                beanFactory);
        } else {
            //通过在类上注解@SofaReference的解析
            Class<?> beanClassType = BeanDefinitionUtil.resolveBeanClassType(beanDefinition);
            if (beanClassType == null) {
                SofaLogger.warn("Bean class type cant be resolved from bean of {}", beanId);
                return;
            }
            generateSofaServiceDefinitionOnClass(beanId, beanClassType, beanDefinition, beanFactory);
        }
    }

    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、处理通过Configuration定义的Bean
     * 	//==============================================================================
     * @param beanId
     * @param beanDefinition
     * @param beanFactory
     */
    private void generateSofaServiceDefinitionOnMethod(String beanId,
                                                       AnnotatedBeanDefinition beanDefinition,
                                                       ConfigurableListableBeanFactory beanFactory) {
        Class<?> returnType;
        Class<?> declaringClass;
        List<Method> candidateMethods = new ArrayList<>();

        MethodMetadata methodMetadata = beanDefinition.getFactoryMethodMetadata();
        try {
            returnType = ClassUtils.forName(methodMetadata.getReturnTypeName(), null);
            declaringClass = ClassUtils.forName(methodMetadata.getDeclaringClassName(), null);
        } catch (Throwable throwable) {
            // it's impossible to catch throwable here
            SofaLogger.error("Failed to parse factoryBeanMethod of BeanDefinition( {} )", beanId, throwable);
            return;
        }
        if (methodMetadata instanceof StandardMethodMetadata) {
           candidateMethods.add(((StandardMethodMetadata) methodMetadata).getIntrospectedMethod());
        } else {
            for (Method m : declaringClass.getDeclaredMethods()) {
                // check methodName and return type
                if (!m.getName().equals(methodMetadata.getMethodName())
                        || !m.getReturnType().getTypeName().equals(methodMetadata.getReturnTypeName())) {
                    continue;
                }

                // check bean method
                // 需要@Bean 声明的方法
                if (!AnnotatedElementUtils.hasAnnotation(m, Bean.class)) {
                    continue;
                }

                //方法名、@Bean的name,value属性 作为beanId的可选匹配
                Bean bean = m.getAnnotation(Bean.class);
                Set<String> beanNames = new HashSet<>();
                beanNames.add(m.getName());
                if (bean != null) {
                    beanNames.addAll(Arrays.asList(bean.name()));
                    beanNames.addAll(Arrays.asList(bean.value()));
                }

                // check bean name
                if (!beanNames.contains(beanId)) {
                    continue;
                }

                candidateMethods.add(m);
            }
        }

        if (candidateMethods.size() == 1) {
            SofaService sofaServiceAnnotation = candidateMethods.get(0).getAnnotation(
                SofaService.class);
            if (sofaServiceAnnotation == null) {
                sofaServiceAnnotation = returnType.getAnnotation(SofaService.class);
            }
            generateSofaServiceDefinition(beanId, sofaServiceAnnotation, returnType,
                beanDefinition, beanFactory);
            generateSofaReferenceDefinition(beanId, candidateMethods.get(0), beanFactory);
        } else if (candidateMethods.size() > 1) {
            //超出一处声明同一个beanId的SofaService则抛出异常
            //同一方法注入同一beanId的SofaReference则跑错
            for (Method m : candidateMethods) {
                if (AnnotatedElementUtils.hasAnnotation(m, SofaService.class)
                    || AnnotatedElementUtils.hasAnnotation(returnType, SofaService.class)) {
                    throw new FatalBeanException(
                        "multi @Bean-method with same name try to publish SofaService in "
                                + declaringClass.getCanonicalName());
                }

                if (Stream.of(m.getParameterAnnotations())
                        .flatMap(Stream::of).anyMatch(annotation -> annotation instanceof SofaReference)) {
                    throw new FatalBeanException(
                            "multi @Bean-method with same name try to reference SofaService in"
                                    + declaringClass.getCanonicalName());
                }
            }
        }
    }

    private void generateSofaReferenceDefinition(String beanId, Method method,
                                                 ConfigurableListableBeanFactory beanFactory) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; ++i) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof SofaReference) {
                    doGenerateSofaReferenceDefinition(beanFactory.getBeanDefinition(beanId),
                        (SofaReference) annotation, parameterTypes[i], beanFactory);
                }
            }
        }
    }


    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、生成@SofaReference的beanDefinition
     * 	//2、仅支持JVM类型服务
     * 	//3、referenceid为ReferenceFactoryBean#interfaceType:uniqueId
     * 	//4、原BeanDefinition的dependenyOn为新创建的BeanDefinition
     * 	//==============================================================================
     */
    @SuppressWarnings("unchecked")
    private void doGenerateSofaReferenceDefinition(BeanDefinition beanDefinition,
                                                   SofaReference sofaReference,
                                                   Class<?> parameterType,
                                                   ConfigurableListableBeanFactory beanFactory) {
        Assert.isTrue(
            JvmBinding.JVM_BINDING_TYPE.getType().equals(sofaReference.binding().bindingType()),
            "Only jvm type of @SofaReference on parameter is supported.");
        AnnotationWrapperBuilder<SofaReference> wrapperBuilder = AnnotationWrapperBuilder.wrap(
            sofaReference).withBinder(binder);
        sofaReference = wrapperBuilder.build();
        Class<?> interfaceType = sofaReference.interfaceType();
        //未指定@SofaReference的interfaceType则使用方法名
        if (interfaceType.equals(void.class)) {
            interfaceType = parameterType;
        }
        String uniqueId = sofaReference.uniqueId();
        String referenceId = SofaBeanNameGenerator.generateSofaReferenceBeanName(interfaceType,
            uniqueId);

        // build sofa reference definition
        if (!beanFactory.containsBeanDefinition(referenceId)) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition();
            builder.getRawBeanDefinition().setScope(beanDefinition.getScope());
            builder.getRawBeanDefinition().setLazyInit(beanDefinition.isLazyInit());
            builder.getRawBeanDefinition().setBeanClass(ReferenceFactoryBean.class);
            builder.addPropertyValue(AbstractContractDefinitionParser.UNIQUE_ID_PROPERTY, uniqueId);
            builder.addPropertyValue(AbstractContractDefinitionParser.INTERFACE_CLASS_PROPERTY,
                interfaceType);
            builder.addPropertyValue(AbstractContractDefinitionParser.BINDINGS,
                getSofaReferenceBinding(sofaReference, sofaReference.binding()));
            builder.addPropertyValue(AbstractContractDefinitionParser.DEFINITION_BUILDING_API_TYPE,
                true);
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(referenceId,
                builder.getBeanDefinition());
        }

        // add bean dependency relationship
        if (beanDefinition.getDependsOn() == null) {
            beanDefinition.setDependsOn(referenceId);
        } else {
            String[] added = ObjectUtils.addObjectToArray(beanDefinition.getDependsOn(),
                referenceId);
            beanDefinition.setDependsOn(added);
        }
    }

    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、处理通过类注解@SofaService的BeanDefinition
     * 	//==============================================================================
     */
    private void generateSofaServiceDefinitionOnClass(String beanId, Class<?> beanClass,
                                                      BeanDefinition beanDefinition,
                                                      ConfigurableListableBeanFactory beanFactory) {
        SofaService sofaServiceAnnotation = beanClass.getAnnotation(SofaService.class);
        generateSofaServiceDefinition(beanId, sofaServiceAnnotation, beanClass, beanDefinition,
            beanFactory);
    }

    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、通过SofaService注解生成SofaService BeanDefinition
     * 	//2、serviceId为 ServiceFactoryBean#interfaceName:uniqueId
     * 	//==============================================================================
     */
    @SuppressWarnings("unchecked")
    private void generateSofaServiceDefinition(String beanId, SofaService sofaServiceAnnotation,
                                               Class<?> beanClass, BeanDefinition beanDefinition,
                                               ConfigurableListableBeanFactory beanFactory) {
        if (sofaServiceAnnotation == null) {
            return;
        }
        AnnotationWrapperBuilder<SofaService> wrapperBuilder = AnnotationWrapperBuilder.wrap(
            sofaServiceAnnotation).withBinder(binder);
        sofaServiceAnnotation = wrapperBuilder.build();

        Class<?> interfaceType = sofaServiceAnnotation.interfaceType();
        //未指定intefaceType属性时自动适配Bean类型
        if (interfaceType.equals(void.class)) {
            Class<?> interfaces[] = beanClass.getInterfaces();

            if (beanClass.isInterface() || interfaces == null || interfaces.length == 0) {
                interfaceType = beanClass;
            } else if (interfaces.length == 1) {
                interfaceType = interfaces[0];
            } else {
                throw new FatalBeanException("Bean " + beanId + " has more than one interface.");
            }
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition();
        String serviceId = SofaBeanNameGenerator.generateSofaServiceBeanName(interfaceType,
            sofaServiceAnnotation.uniqueId());

        if (!beanFactory.containsBeanDefinition(serviceId)) {
            builder.getRawBeanDefinition().setScope(beanDefinition.getScope());
            builder.setLazyInit(beanDefinition.isLazyInit());
            builder.getRawBeanDefinition().setBeanClass(ServiceFactoryBean.class);
            builder.addPropertyValue(AbstractContractDefinitionParser.INTERFACE_CLASS_PROPERTY,
                interfaceType);
            builder.addPropertyValue(AbstractContractDefinitionParser.UNIQUE_ID_PROPERTY,
                sofaServiceAnnotation.uniqueId());
            builder.addPropertyValue(AbstractContractDefinitionParser.BINDINGS,
                getSofaServiceBinding(sofaServiceAnnotation, sofaServiceAnnotation.bindings()));
            builder.addPropertyReference(ServiceDefinitionParser.REF, beanId);
            builder.addPropertyValue(ServiceDefinitionParser.BEAN_ID, beanId);
            builder.addPropertyValue(AbstractContractDefinitionParser.DEFINITION_BUILDING_API_TYPE,
                true);
            builder.addDependsOn(beanId);
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(serviceId,
                builder.getBeanDefinition());
        } else {
            SofaLogger.error("SofaService was already registered: {}", serviceId);
        }
    }

    /**
     *	//==========================JUSTINWARE==========================================
     * 	//1、获取SofaServiceBings的解析
     * 	//2、
     * 	//==============================================================================
     */
    private List<Binding> getSofaServiceBinding(SofaService sofaServiceAnnotation,
                                                SofaServiceBinding[] sofaServiceBindings) {
        List<Binding> bindings = new ArrayList<>();
        for (SofaServiceBinding sofaServiceBinding : sofaServiceBindings) {
            BindingConverter bindingConverter = bindingConverterFactory
                .getBindingConverter(new BindingType(sofaServiceBinding.bindingType()));
            if (bindingConverter == null) {
                throw new ServiceRuntimeException(
                    "Can not found binding converter for binding type "
                            + sofaServiceBinding.bindingType());
            }
            BindingConverterContext bindingConverterContext = new BindingConverterContext();
            bindingConverterContext.setInBinding(false);
            bindingConverterContext.setApplicationContext(applicationContext);
            bindingConverterContext.setAppName(sofaRuntimeContext.getAppName());
            bindingConverterContext.setAppClassLoader(sofaRuntimeContext.getAppClassLoader());
            Binding binding = bindingConverter.convert(sofaServiceAnnotation, sofaServiceBinding,
                bindingConverterContext);
            bindings.add(binding);
        }
        return bindings;
    }

    /**
     * get sofa reference binding annotated on parameter. At present, only jvm sofa reference is supported .
     * @param sofaReferenceAnnotation
     * @param sofaReferenceBinding
     * @return
     */
    private List<Binding> getSofaReferenceBinding(SofaReference sofaReferenceAnnotation,
                                                  SofaReferenceBinding sofaReferenceBinding) {
        if (!JvmBinding.XmlConstants.BINDING_TYPE.equals(sofaReferenceBinding.bindingType())) {
            throw new ServiceRuntimeException(
                "Only jvm sofa reference binding is supported to annotate on parameter.");
        }
        List<Binding> bindings = new ArrayList<>();
        BindingConverter bindingConverter = bindingConverterFactory
            .getBindingConverter(new BindingType(sofaReferenceBinding.bindingType()));
        if (bindingConverter == null) {
            throw new ServiceRuntimeException("Can not found binding converter for binding type "
                                              + sofaReferenceBinding.bindingType());
        }
        BindingConverterContext bindingConverterContext = new BindingConverterContext();
        bindingConverterContext.setInBinding(true);
        bindingConverterContext.setApplicationContext(applicationContext);
        bindingConverterContext.setAppName(sofaRuntimeContext.getAppName());
        bindingConverterContext.setAppClassLoader(sofaRuntimeContext.getAppClassLoader());
        Binding binding = bindingConverter.convert(sofaReferenceAnnotation, sofaReferenceBinding,
            bindingConverterContext);
        bindings.add(binding);
        return bindings;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    class DefaultPlaceHolderBinder implements PlaceHolderBinder {
        @Override
        public String bind(String text) {
            return environment.resolvePlaceholders(text);
        }
    }
}