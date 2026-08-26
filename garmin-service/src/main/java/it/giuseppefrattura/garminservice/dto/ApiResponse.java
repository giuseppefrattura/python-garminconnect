package it.giuseppefrattura.garminservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard envelope for API responses: either a success payload or an error detail.
 * Null fields are omitted so success/error JSON shapes stay minimal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String status, T data, String detail) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", data, null);
    }

    public static <T> ApiResponse<T> error(String detail) {
        return new ApiResponse<>("error", null, detail);
    }

    public boolean isError() {
        return "error".equals(status);
    }
}
