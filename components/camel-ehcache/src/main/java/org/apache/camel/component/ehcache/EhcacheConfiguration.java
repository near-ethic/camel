/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.ehcache;

import java.io.IOException;
import java.net.URL;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriParams;
import org.apache.camel.util.EndpointHelper;
import org.apache.camel.util.IntrospectionSupport;
import org.apache.camel.util.ResourceHelper;
import org.ehcache.CacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.event.EventFiring;
import org.ehcache.event.EventOrdering;
import org.ehcache.event.EventType;
import org.ehcache.xml.XmlConfiguration;

@UriParams
public class EhcacheConfiguration {
    public static final String PREFIX_CACHE = "cache.";
    public static final String PREFIX_POOL = "pool.";

    private final CamelContext context;
    private final String cacheName;

    @UriParam
    private String configUri;

    @UriParam(defaultValue = "true")
    private boolean createCacheIfNotExist = true;

    @UriParam(label = "producer")
    private String action;
    @UriParam(label = "producer")
    private String key;

    @UriParam
    private CacheManager cacheManager;
    @UriParam(label = "advanced")
    private CacheConfiguration<?, ?> defaultCacheConfiguration;
    @UriParam(label = "advanced")
    private ResourcePools defaultCacheResourcePools;

    @UriParam(label = "advanced", prefix = PREFIX_CACHE, multiValue = true, javaType = "java.lang.String")
    private Map<String, CacheConfiguration> cacheConfigurations;
    @UriParam(label = "advanced", prefix = PREFIX_POOL, multiValue = true, javaType = "java.lang.String")
    private Map<String, ResourcePools> cacheResourcePools;

    @UriParam(
        label = "consumer",
        enums = "ORDERED,UNORDERED",
        defaultValue = "ORDERED")
    private EventOrdering eventOrdering = EventOrdering.ORDERED;

    @UriParam(
        label = "consumer",
        enums = "ASYNCHRONOUS, SYNCHRONOUS",
        defaultValue = "ASYNCHRONOUS")
    private EventFiring eventFiring = EventFiring.ASYNCHRONOUS;

    @UriParam(
        label = "consumer",
        enums = "EVICTED,EXPIRED,REMOVED,CREATED,UPDATED",
        defaultValue = "EVICTED,EXPIRED,REMOVED,CREATED,UPDATED")
    private Set<EventType> eventTypes = EnumSet.of(EventType.values()[0], EventType.values());

    EhcacheConfiguration(String cacheName) {
        this(null, cacheName);
    }

    EhcacheConfiguration(CamelContext context, String cacheName) {
        this.context = context;
        this.cacheName = cacheName;
    }

    public CamelContext getContext() {
        return context;
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getConfigUri() {
        return configUri;
    }

    public URL getConfigUriAsUrl() throws IOException {
        return context != null
            ? ResourceHelper.resolveMandatoryResourceAsUrl(context.getClassResolver(), configUri)
            : new URL(configUri);
    }

    /**
     * URI pointing to the Ehcache XML configuration file's location
     */
    public void setConfigUri(String configUri) {
        this.configUri = configUri;
    }

    public boolean isCreateCacheIfNotExist() {
        return createCacheIfNotExist;
    }

    /**
     * Configure if a cache need to be created if it does exist or can't be
     * pre-configured.
     */
    public void setCreateCacheIfNotExist(boolean createCacheIfNotExist) {
        this.createCacheIfNotExist = createCacheIfNotExist;
    }

    public String getAction() {
        return action;
    }

    /**
     * To configure the default cache action. If an action is set in the message
     * header, then the operation from the header takes precedence.
     */
    public void setAction(String action) {
        this.action = action;
    }

    public String getKey() {
        return key;
    }

    /**
     * To configure the default action key. If a key is set in the message
     * header, then the key from the header takes precedence.
     */
    public void setKey(String key) {
        this.key = key;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * The cache manager
     */
    public void setCacheManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public boolean hasCacheManager() {
        return this.cacheManager != null;
    }

    public EventOrdering getEventOrdering() {
        return eventOrdering;
    }

    /**
     * Set the the delivery mode (ordered, unordered)
     */
    public void setEventOrdering(String eventOrdering) {
        setEventOrdering(EventOrdering.valueOf(eventOrdering));
    }

    public void setEventOrdering(EventOrdering eventOrdering) {
        this.eventOrdering = eventOrdering;
    }

    public EventFiring getEventFiring() {
        return eventFiring;
    }

    /**
     * Set the the delivery mode (synchronous, asynchronous)
     */
    public void setEventFiring(String eventFiring) {
        setEventFiring(EventFiring.valueOf(eventFiring));
    }

    public void setEventFiring(EventFiring eventFiring) {
        this.eventFiring = eventFiring;
    }

    public Set<EventType> getEventTypes() {
        return eventTypes;
    }

    /**
     * Set the type of events to listen for
     */
    public void setEventTypes(String eventTypesString) {
        Set<EventType> eventTypes = new HashSet<>();
        String[] events = eventTypesString.split(",");
        for (String event : events) {
            eventTypes.add(EventType.valueOf(event));
        }

        setEventTypes(eventTypes);
    }

    public void setEventTypes(Set<EventType> eventTypes) {
        this.eventTypes = new HashSet<>(eventTypes);
    }

    // ****************************
    // Cache Configuration
    // ****************************

    /**
     * The default cache configuration to be used to create caches.
     */
    public void setDefaultCacheConfiguration(CacheConfiguration<?, ?> defaultCacheConfiguration) {
        this.defaultCacheConfiguration = defaultCacheConfiguration;
    }

    public CacheConfiguration<?, ?> getDefaultCacheConfiguration() {
        return defaultCacheConfiguration;
    }

    /**
     * The cache configuration to be used for cache cacheName.
     */
    public void addCacheConfiguration(String cacheName, CacheConfiguration cacheConfiguration) {
        if (cacheConfigurations == null) {
            cacheConfigurations = new HashMap<>();
        }

        cacheConfigurations.put(cacheName, cacheConfiguration);
    }

    EhcacheConfiguration addCacheConfigurationFromParameters(Map<String, Object> parameters) {
        Map<String, Object> models = IntrospectionSupport.extractProperties(parameters, PREFIX_CACHE);
        for (Map.Entry<String, Object> entry : models.entrySet()) {
            addCacheConfiguration(
                entry.getKey(),
                EndpointHelper.resolveParameter(
                    context,
                    (String)entry.getValue(),
                    CacheConfiguration.class
                )
            );
        }

        return this;
    }

    public CacheConfiguration getCacheConfiguration(String cacheName) {
        return cacheConfigurations != null
            ? cacheConfigurations.getOrDefault(cacheName, defaultCacheConfiguration)
            : defaultCacheConfiguration;
    }

    // ****************************
    // Cache Resource Pools
    // ****************************

    public ResourcePools getDefaultCacheResourcePools() {
        return defaultCacheResourcePools;
    }

    /**
     * The default resource pools to be used to create caches.
     */
    public void setDefaultCacheResourcePools(ResourcePools defaultCacheResourcePools) {
        this.defaultCacheResourcePools = defaultCacheResourcePools;
    }

    /**
     * The resource pools to be used for cache cacheName.
     */
    public void addResourcePools(String cacheName, ResourcePools resourcePools) {
        if (cacheResourcePools == null) {
            cacheResourcePools = new HashMap<>();
        }

        cacheResourcePools.put(cacheName, resourcePools);
    }

    EhcacheConfiguration addResourcePoolsFromParameters(Map<String, Object> parameters) {
        Map<String, Object> models = IntrospectionSupport.extractProperties(parameters, PREFIX_POOL);
        for (Map.Entry<String, Object> entry : models.entrySet()) {
            addResourcePools(
                entry.getKey(),
                EndpointHelper.resolveParameter(
                    context,
                    (String)entry.getValue(),
                    ResourcePools.class
                )
            );
        }

        return this;
    }

    public ResourcePools getResourcePools(String cacheName) {
        return cacheResourcePools != null
            ? cacheResourcePools.getOrDefault(cacheName, defaultCacheResourcePools)
            : defaultCacheResourcePools;
    }

    // ****************************
    // Helpers
    // ****************************

    static EhcacheConfiguration create(CamelContext context, String remaining, Map<String, Object> parameters) throws Exception {
        EhcacheConfiguration configuration = new EhcacheConfiguration(context, remaining);
        configuration.addCacheConfigurationFromParameters(parameters);
        configuration.addResourcePoolsFromParameters(parameters);

        EndpointHelper.setReferenceProperties(context, configuration, parameters);
        EndpointHelper.setProperties(context, configuration, parameters);

        return configuration;
    }

    CacheManager createCacheManager() throws IOException {
        CacheManager manager;

        if (cacheManager != null) {
            manager = cacheManager;
        } else if (configUri != null) {
            manager = CacheManagerBuilder.newCacheManager(new XmlConfiguration(getConfigUriAsUrl()));
        } else {
            CacheManagerBuilder builder = CacheManagerBuilder.newCacheManagerBuilder();
            if (cacheConfigurations != null) {
                cacheConfigurations.forEach(builder::withCache);
            }

            manager = builder.build();
        }

        return manager;
    }
}
