package com.azneotech.cartservice.dtos;

import com.azneotech.cartservice.models.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequestDto {

    @NotNull
    @Valid
    private DeliveryAddressDto deliveryAddress;
    @NotNull
    private PaymentMethod paymentMethod;

}
