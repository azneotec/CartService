package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.DeliveryAddressDto;
import com.azneotech.cartservice.dtos.OrderItemRequestDto;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.azneotech.cartservice.models.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OrderManagementClientTest {

    private static final String URL = "http://order-management-service/orders";

    private MockRestServiceServer server;
    private OrderManagementClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new OrderManagementClient(restTemplate);
    }

    private static PlaceOrderRequestDto request() {
        return PlaceOrderRequestDto.builder()
                .items(List.of(new OrderItemRequestDto(5L, 2)))
                .deliveryAddress(DeliveryAddressDto.builder().fullName("Alice").city("Pune").build())
                .paymentMethod(PaymentMethod.UPI)
                .expectedTotal(new BigDecimal("219.90"))
                .build();
    }

    @Test
    void placeOrder_postsTheRequestWithTheCallersTokenAndReadsTheConfirmation() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(jsonPath("$.items[0].productId").value(5))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.paymentMethod").value("UPI"))
                .andExpect(jsonPath("$.expectedTotal").value(219.90))
                .andRespond(withSuccess("""
                        {"orderNumber":"ORD-1","status":"PENDING_PAYMENT","paymentStatus":"PENDING","totalAmount":219.90,
                         "totalQuantity":2,"currency":"INR","someFutureField":true}""", MediaType.APPLICATION_JSON));

        OrderResponseDto order = client.placeOrder("Bearer user-token", request());

        assertEquals("ORD-1", order.getOrderNumber());
        assertEquals("PENDING_PAYMENT", order.getStatus());
        assertEquals(new BigDecimal("219.90"), order.getTotalAmount());
        server.verify();
    }

    @Test
    void placeOrder_conflictCarriesTheOrderServicesMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":409,\"error\":\"Conflict\",\"message\":\"These products are no longer available: [5]\"}"));

        CheckoutConflictException e = assertThrows(CheckoutConflictException.class, () -> client.placeOrder("Bearer t", request()));

        assertEquals("These products are no longer available: [5]", e.getMessage());
    }

    @Test
    void placeOrder_conflictWithAnUnreadableBodyStillSaysWhatToDo() {
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_PLAIN).body("nope"));

        CheckoutConflictException e = assertThrows(CheckoutConflictException.class, () -> client.placeOrder("Bearer t", request()));

        assertTrue(e.getMessage().contains("try again"), e.getMessage());
    }

    @Test
    void placeOrder_otherFailuresAreAnOutage() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 4; i++) {
            assertThrows(DownstreamUnavailableException.class, () -> client.placeOrder("Bearer t", request()));
        }
    }

    @Test
    void placeOrder_connectionFailureIsAnOutage() {
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("connection reset")));

        assertThrows(DownstreamUnavailableException.class, () -> client.placeOrder("Bearer t", request()));
    }

    @Test
    void placeOrder_noRegisteredInstanceIsAnOutage() {
        // What the @LoadBalanced RestTemplate throws when Eureka has no order-management-service.
        server.expect(requestTo(URL)).andRespond(req -> {
            throw new IllegalStateException("No instances available for order-management-service");
        });

        assertThrows(DownstreamUnavailableException.class, () -> client.placeOrder("Bearer t", request()));
    }

    @Test
    void placeOrder_confirmationWithoutAnOrderNumberIsAnOutage() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"CONFIRMED\"}", MediaType.APPLICATION_JSON));

        assertThrows(DownstreamUnavailableException.class, () -> client.placeOrder("Bearer t", request()));
    }

    @Test
    void placeOrder_conflictWithoutAMessageStillSaysWhatToDo() {
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":409,\"error\":\"Conflict\"}"));

        CheckoutConflictException e = assertThrows(CheckoutConflictException.class, () -> client.placeOrder("Bearer t", request()));

        assertTrue(e.getMessage().contains("try again"), e.getMessage());
    }

}
