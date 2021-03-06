/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.deltaspike.security.impl.extension;

import org.apache.deltaspike.core.spi.activation.Deactivatable;
import org.apache.deltaspike.core.util.ClassDeactivationUtils;
import org.apache.deltaspike.core.util.metadata.builder.AnnotatedTypeBuilder;
import org.apache.deltaspike.security.api.authorization.SecurityDefinitionException;
import org.apache.deltaspike.security.api.authorization.annotation.Secures;
import org.apache.deltaspike.security.impl.util.SecurityUtils;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import java.lang.annotation.Annotation;

/**
 * Extension for processing typesafe security annotations
 */
public class SecurityExtension implements Extension, Deactivatable
{
    private static final SecurityInterceptorBinding INTERCEPTOR_BINDING = new SecurityInterceptorBindingLiteral();

    private SecurityMetaDataStorage securityMetaDataStorage;

    private Boolean isActivated = null;

    protected void init(@Observes BeforeBeanDiscovery beforeBeanDiscovery)
    {
        isActivated = ClassDeactivationUtils.isActivated(getClass());
        securityMetaDataStorage = new SecurityMetaDataStorage();
    }

    //workaround for OWB
    public SecurityMetaDataStorage getMetaDataStorage()
    {
        return securityMetaDataStorage;
    }

    /**
     * Handles &#064;Secured beans
     */
    @SuppressWarnings("UnusedDeclaration")
    public <X> void processAnnotatedType(@Observes ProcessAnnotatedType<X> event)
    {
        if (!isActivated)
        {
            return;
        }

        AnnotatedTypeBuilder<X> builder = null;
        AnnotatedType<X> type = event.getAnnotatedType();
        
        boolean isSecured = false;

        // Add the security interceptor to the class if the class is annotated
        // with a security binding type
        for (final Annotation annotation : type.getAnnotations())
        {
            if (SecurityUtils.isMetaAnnotatedWithSecurityBindingType(annotation))
            {
                builder = new AnnotatedTypeBuilder<X>().readFromType(type);
                builder.addToClass(INTERCEPTOR_BINDING);
                isSecured = true;
                break;
            }
        }

        // If the class isn't annotated with a security binding type, check if
        // any of its methods are, and if so, add the security interceptor to the
        // method
        if (!isSecured) 
        {
            for (final AnnotatedMethod<? super X> m : type.getMethods()) 
            {
                if (m.isAnnotationPresent(Secures.class)) 
                {
                    registerAuthorizer(m);
                    continue;
                }

                for (final Annotation annotation : m.getAnnotations()) 
                {
                    if (SecurityUtils.isMetaAnnotatedWithSecurityBindingType(annotation))
                    {
                        if (builder == null) 
                        {
                            builder = new AnnotatedTypeBuilder<X>().readFromType(type);
                        }
                        builder.addToMethod(m, INTERCEPTOR_BINDING);
                        isSecured = true;
                        break;
                    }
                }
            }
        }

        // If either the bean or any of its methods are secured, register it
        if (isSecured) 
        {
            getMetaDataStorage().addSecuredType(type);
        }

        if (builder != null) 
        {
            event.setAnnotatedType(builder.create());
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    public void validateBindings(@Observes AfterBeanDiscovery event, BeanManager beanManager)
    {
        if (!isActivated)
        {
            return;
        }

        SecurityMetaDataStorage metaDataStorage = getMetaDataStorage();

        for (final AnnotatedType<?> type : metaDataStorage.getSecuredTypes())
        {
            // Here we simply want to validate that each type that is annotated with
            // one or more security bindings has a valid authorizer for each binding

            for (final Annotation annotation : type.getJavaClass().getAnnotations()) 
            {
                boolean found = false;

                if (SecurityUtils.isMetaAnnotatedWithSecurityBindingType(annotation))
                {
                    // Validate the authorizer
                    for (Authorizer auth : metaDataStorage.getAuthorizers())
                    {
                        if (auth.matchesBinding(annotation)) 
                        {
                            found = true;
                            break;
                        }
                    }

                    if (!found) 
                    {
                        event.addDefinitionError(new SecurityDefinitionException("Secured type " +
                                type.getJavaClass().getName() +
                                " has no matching authorizer method for security binding @" +
                                annotation.annotationType().getName()));
                    }
                }
            }

            for (final AnnotatedMethod<?> method : type.getMethods()) 
            {
                for (final Annotation annotation : method.getAnnotations()) 
                {
                    if (SecurityUtils.isMetaAnnotatedWithSecurityBindingType(annotation))
                    {
                        metaDataStorage.registerSecuredMethod(type.getJavaClass(), method.getJavaMember());
                        break;
                    }
                }
            }
        }

        // Clear securedTypes, we don't require it any more
        metaDataStorage.resetSecuredTypes();
    }

    /**
     * Registers the specified authorizer method (i.e. a method annotated with
     * the @Secures annotation)
     *
     * @throws SecurityDefinitionException
     */
    private void registerAuthorizer(AnnotatedMethod<?> annotatedMethod)
    {
        if (!annotatedMethod.getJavaMember().getReturnType().equals(Boolean.class) &&
                !annotatedMethod.getJavaMember().getReturnType().equals(Boolean.TYPE))
        {
            throw new SecurityDefinitionException("Invalid authorizer method [" +
                    annotatedMethod.getJavaMember().getDeclaringClass().getName() + "." +
                    annotatedMethod.getJavaMember().getName() + "] - does not return a boolean.");
        }

        // Locate the binding type
        Annotation binding = null;

        for (Annotation annotation : annotatedMethod.getAnnotations())
        {
            if (SecurityUtils.isMetaAnnotatedWithSecurityBindingType(annotation))
            {
                if (binding != null)
                {
                    throw new SecurityDefinitionException("Invalid authorizer method [" +
                            annotatedMethod.getJavaMember().getDeclaringClass().getName() + "." +
                            annotatedMethod.getJavaMember().getName() + "] - declares multiple security binding types");
                }
                binding = annotation;
            }
        }

        Authorizer authorizer = new Authorizer(binding, annotatedMethod);
        getMetaDataStorage().addAuthorizer(authorizer);
    }
}
