package io.advantageous.qbit.spring.config;

import io.advantageous.qbit.spring.ServiceQueueFactoryBean;
import io.advantageous.qbit.spring.ServiceQueueRegistry;
import io.advantageous.qbit.spring.annotation.NoAsyncInterface;
import io.advantageous.qbit.spring.annotation.QBitService;
import io.advantageous.qbit.spring.properties.AppProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry post processor that automatically creates service queues for QBit services.
 *
 * @author geoffc@gmail.com (Geoff Chandler)
 */
@Configuration
@EnableConfigurationProperties({AppProperties.class})
public class ServiceQueueCreator implements BeanFactoryPostProcessor {

    private final Map<String, Map<String, Object>> beanMetadataMap = new HashMap<>();

    @Bean
    public ServiceQueueRegistry serviceQueueRegistry() {
        return new ServiceQueueRegistry(beanMetadataMap);
    }

    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
        final BeanDefinitionRegistry registry = ((BeanDefinitionRegistry) beanFactory);
        Arrays.asList(registry.getBeanDefinitionNames())
                .stream()
                .map(registry::getBeanDefinition)
                .filter(def -> def instanceof AnnotatedBeanDefinition)
                .map(def -> (AnnotatedBeanDefinition) def)
                .filter(def -> def.getFactoryMethodMetadata() != null)
                .filter(def -> def.getFactoryMethodMetadata().isAnnotated(QBitService.class.getCanonicalName()))
                .forEach(def -> {

                    final String beanName = def.getFactoryMethodName();
                    /*
                    Lookup the annotation metadata on the bean definition and put it in a map we can use later to join
                    event managers (see PlatformConfiguration.ServiceQueueInitializer)
                     */
                    final String queueBeanName = beanName + "Queue";
                    //TODO: def might not have a factory method name if it was a component scan
                    final Map<String, Object> metadata = def.getFactoryMethodMetadata()
                            .getAnnotationAttributes(QBitService.class.getCanonicalName());
                    final String endpointLocation;
                    final Class asyncInterface = (Class) metadata.get("asyncInterface");
                    if (asyncInterface != null) {
                        endpointLocation = asyncInterface.getName();
                    } else {
                        endpointLocation = beanName;
                    }
                    metadata.put("endpointLocation", endpointLocation);
                    beanMetadataMap.put(queueBeanName, metadata);

                    /*
                    Generate a service queue for service implementations annotated with @QBitService
                     */
                    final BeanDefinition serviceQueueDefinition = BeanDefinitionBuilder
                            .rootBeanDefinition(ServiceQueueFactoryBean.class)
                            .addPropertyValue("beanName", queueBeanName)
                            .addPropertyValue("serviceAddress", endpointLocation)
                            .addPropertyReference("serviceObject", beanName)
                            .addPropertyReference("requestQueueBuilder", "requestQueueBuilder")
                            .addPropertyReference("serviceMethodHandler", "dynamicInvokingBoonServiceMethodCallHandler")
                            .addPropertyReference("responseQueue", "sharedResponseQueue")
                            .getBeanDefinition();
                    registry.registerBeanDefinition(queueBeanName, serviceQueueDefinition);

                    /*
                    Now generate a proxy for the service queue using the asyncInterface
                     */
                    if (!NoAsyncInterface.class.equals(asyncInterface)) {
                        final RootBeanDefinition proxyDef = new RootBeanDefinition(asyncInterface);
                        proxyDef.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE);
                        proxyDef.setFactoryBeanName(queueBeanName);
                        proxyDef.setFactoryMethodName("createProxy");
                        final ConstructorArgumentValues factoryParams = new ConstructorArgumentValues();
                        factoryParams.addIndexedArgumentValue(0, asyncInterface);
                        proxyDef.setConstructorArgumentValues(factoryParams);
                        registry.registerBeanDefinition(beanName + "Proxy", proxyDef);
                    }
                });
    }
}
