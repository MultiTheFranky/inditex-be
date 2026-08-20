package com.inditex.similarproducts.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * {@link WebClient} used to reach the existing product APIs. A dedicated, bounded connection pool
 * keeps the slow endpoints from starving the fast ones and puts a hard cap on the load we can push
 * upstream.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductsProperties.class)
public class WebClientConfig {

	@Bean
	public WebClient productsWebClient(WebClient.Builder builder, ProductsProperties properties) {
		ConnectionProvider connectionProvider = ConnectionProvider.builder("products")
				.maxConnections(properties.maxConnections())
				.pendingAcquireTimeout(properties.pendingAcquireTimeout())
				.maxIdleTime(Duration.ofSeconds(30))
				.maxLifeTime(Duration.ofMinutes(5))
				.evictInBackground(Duration.ofSeconds(30))
				.lifo()
				.build();

		HttpClient httpClient = HttpClient.create(connectionProvider)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
				.responseTimeout(properties.detailTimeout())
				.compress(true);

		return builder.baseUrl(properties.baseUrl())
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.build();
	}
}
