package com.azneotech.cartservice.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAddressDto {

    @NotBlank
    @Size(max = 100)
    private String fullName;
    @NotBlank
    @Size(max = 20)
    private String phoneNumber;
    @NotBlank
    @Size(max = 200)
    private String addressLine1;
    @Size(max = 200)
    private String addressLine2;
    @NotBlank
    @Size(max = 100)
    private String city;
    @NotBlank
    @Size(max = 100)
    private String state;
    @NotBlank
    @Size(max = 20)
    private String postalCode;
    @NotBlank
    @Size(max = 100)
    private String country;

}
