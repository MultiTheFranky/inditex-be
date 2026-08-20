package com.inditex.similarproducts.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.inditex.similarproducts.domain.ProductDetail;
import com.inditex.similarproducts.domain.SimilarProductsService;

import reactor.core.publisher.Mono;

/**
 * Implements the agreed contract (similarProducts.yaml).
 */
@RestController
public class SimilarProductsController {

	private final SimilarProductsService similarProducts;

	public SimilarProductsController(SimilarProductsService similarProducts) {
		this.similarProducts = similarProducts;
	}

	@GetMapping(path = "/product/{productId}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<ProductDetail>> similar(@PathVariable String productId) {
		return similarProducts.similarProducts(productId);
	}
}
