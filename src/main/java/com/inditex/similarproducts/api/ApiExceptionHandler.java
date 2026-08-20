package com.inditex.similarproducts.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.inditex.similarproducts.domain.CatalogUnavailableException;
import com.inditex.similarproducts.domain.ProductNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ProductNotFoundException.class)
	public ProblemDetail handleNotFound(ProductNotFoundException error) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
		problem.setTitle("Product not found");
		problem.setDetail(error.getMessage());
		return problem;
	}

	@ExceptionHandler(CatalogUnavailableException.class)
	public ProblemDetail handleUnavailable(CatalogUnavailableException error) {
		log.warn("Catalog unavailable: {}", error.getMessage(), error.getCause());
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
		problem.setTitle("Product catalog unavailable");
		problem.setDetail(error.getMessage());
		return problem;
	}
}
