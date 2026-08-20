package com.inditex.similarproducts.client;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.inditex.similarproducts.config.ProductsProperties;
import com.inditex.similarproducts.domain.CatalogUnavailableException;
import com.inditex.similarproducts.domain.ProductCatalog;
import com.inditex.similarproducts.domain.ProductDetail;
import com.inditex.similarproducts.domain.ProductNotFoundException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Adapter over the existing APIs described in {@code existingApis.yaml}.
 *
 * <p>Every call is guarded by a timeout, a bounded retry for transient failures and a circuit
 * breaker, so a degraded upstream neither blocks our event loop nor gets hammered while it recovers.
 */
@Component
public class ProductApiClient implements ProductCatalog {

	static final String SIMILAR_IDS_BREAKER = "similarIds";
	static final String DETAIL_BREAKER = "productDetail";

	private static final ParameterizedTypeReference<List<String>> ID_LIST = new ParameterizedTypeReference<>() {
	};

	private final WebClient webClient;
	private final CircuitBreaker similarIdsBreaker;
	private final CircuitBreaker detailBreaker;
	private final Duration similarIdsTimeout;
	private final Duration detailTimeout;
	private final Retry retry;

	public ProductApiClient(WebClient productsWebClient, CircuitBreakerRegistry breakers,
			ProductsProperties properties) {
		this.webClient = productsWebClient;
		this.similarIdsBreaker = breakers.circuitBreaker(SIMILAR_IDS_BREAKER);
		this.detailBreaker = breakers.circuitBreaker(DETAIL_BREAKER);
		this.similarIdsTimeout = properties.similarIdsTimeout();
		this.detailTimeout = properties.detailTimeout();
		this.retry = Retry.backoff(properties.maxRetries(), properties.retryBackoff())
				.jitter(0.5d)
				.filter(ProductApiClient::isRetryable)
				.onRetryExhaustedThrow((spec, signal) -> signal.failure());
	}

	@Override
	public Flux<String> similarIds(String productId) {
		return webClient.get()
				.uri("/product/{productId}/similarids", productId)
				.retrieve()
				.onStatus(HttpStatus.NOT_FOUND::equals, response -> notFound(response, productId))
				.bodyToMono(ID_LIST)
				.timeout(similarIdsTimeout)
				.transformDeferred(CircuitBreakerOperator.of(similarIdsBreaker))
				.retryWhen(retry)
				.onErrorMap(error -> !(error instanceof ProductNotFoundException),
						error -> unavailable("similar ids of product " + productId, error))
				.flatMapMany(Flux::fromIterable);
	}

	@Override
	public Mono<ProductDetail> detail(String productId) {
		return webClient.get()
				.uri("/product/{productId}", productId)
				.retrieve()
				.onStatus(HttpStatus.NOT_FOUND::equals, response -> notFound(response, productId))
				.bodyToMono(ProductDetail.class)
				.timeout(detailTimeout)
				.transformDeferred(CircuitBreakerOperator.of(detailBreaker))
				.retryWhen(retry)
				.onErrorResume(ProductNotFoundException.class, error -> Mono.empty())
				.onErrorMap(error -> unavailable("detail of product " + productId, error));
	}

	private static Mono<Throwable> notFound(ClientResponse response, String productId) {
		return response.releaseBody().then(Mono.just(new ProductNotFoundException(productId)));
	}

	private static CatalogUnavailableException unavailable(String what, Throwable cause) {
		return new CatalogUnavailableException("Could not obtain the " + what, cause);
	}

	/**
	 * Only failures that are likely to succeed on an immediate second attempt are retried. Timeouts
	 * are excluded on purpose: the attempt already consumed its whole budget and retrying it would
	 * only add load to an upstream that is already struggling.
	 */
	private static boolean isRetryable(Throwable error) {
		if (isTimeout(error)) {
			return false;
		}
		if (error instanceof WebClientResponseException response) {
			return response.getStatusCode().is5xxServerError();
		}
		return error instanceof WebClientRequestException || error instanceof IOException;
	}

	private static boolean isTimeout(Throwable error) {
		for (Throwable cause = error; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
			if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException) {
				return true;
			}
		}
		return false;
	}
}
