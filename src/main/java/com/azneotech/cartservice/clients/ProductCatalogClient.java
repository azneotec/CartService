package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.ProductCatalogDto;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Calls ProductCatalogService by its Eureka name through the {@code @LoadBalanced} RestTemplate. */
@Service
public class ProductCatalogClient implements IProductCatalogClient {

    private static final String SERVICE = "product-catalog-service";
    private static final String PRODUCT_URL = "http://" + SERVICE + "/products/{id}";

    private final RestTemplate restTemplate;

    public ProductCatalogClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ProductCatalogDto getProduct(Long productId) {
        try {
            ProductCatalogDto product = restTemplate.getForObject(PRODUCT_URL, ProductCatalogDto.class, productId);
            if (product == null || product.getPrice() == null) {
                throw new DownstreamUnavailableException(SERVICE);
            }
            return product;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException(productId);
        } catch (RestClientException | IllegalStateException e) {
            // IllegalStateException is what the load balancer throws when no instance is registered.
            throw new DownstreamUnavailableException(SERVICE, e);
        }
    }

}
