package com.inditex.similarproducts.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.inditex.similarproducts.client.CachingProductCatalog;
import com.inditex.similarproducts.client.ProductApiClient;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "products.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

	@Bean
	@Primary
	public CachingProductCatalog cachingProductCatalog(ProductApiClient delegate, ProductsProperties properties,
			MeterRegistry meterRegistry) {
		CachingProductCatalog catalog = new CachingProductCatalog(delegate, properties.cache());
		CaffeineCacheMetrics.monitor(meterRegistry, catalog.similarIdsCache().synchronous(), "similarIds");
		CaffeineCacheMetrics.monitor(meterRegistry, catalog.detailCache().synchronous(), "productDetail");
		return catalog;
	}
}
