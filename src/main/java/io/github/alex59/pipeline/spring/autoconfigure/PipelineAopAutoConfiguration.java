package io.github.alex59.pipeline.spring.autoconfigure;

import org.springframework.aop.Advisor;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Role;
import org.springframework.core.type.AnnotationMetadata;

/** Optional Spring advisor integration; no AspectJ weaving or annotation interpreter. */
@AutoConfiguration(after = PipelineAutoConfiguration.class)
@ConditionalOnClass(Advisor.class)
@ConditionalOnBean(PipelineProperties.class)
@ConditionalOnProperty(prefix = "reactive.pipeline.aop", name = "enabled", havingValue = "true")
@Import(PipelineAopAutoConfiguration.ProxyRegistration.class)
public class PipelineAopAutoConfiguration {
    /**
     * @param beans application bean factory used for explicit policy lookup
     * @param properties immutable settings
     * @return infrastructure advisor; a bean with this name can replace the default
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(name = "reactivePolicyAdvisor")
    Advisor reactivePolicyAdvisor(BeanFactory beans, PipelineProperties properties) {
        return new ReactivePolicyAdvisor(beans, properties.aop().order());
    }

    static final class ProxyRegistration implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            // Reuse/escalate Spring's shared proxy creator rather than adding a second one.
            AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        }
    }
}
