package com.wine.cellar.common;

/**
 * 统一返回包装：成功 {ok:true, data:...}，失败 {ok:false, message:"..."}
 */
public class ApiResponse<T> {

    private boolean ok;
    private String message;
    private String code;      // 机器可读的阻断类型（仅失败时可能有值）
    private String action;    // 可继续处理的入口（前端路由名，仅失败时可能有值）
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean ok, String message, T data) {
        this(ok, message, null, null, data);
    }

    public ApiResponse(boolean ok, String message, String code, String action, T data) {
        this.ok = ok;
        this.message = message;
        this.code = code;
        this.action = action;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }

    public static <T> ApiResponse<T> fail(String message, String code, String action) {
        return new ApiResponse<>(false, message, code, action, null);
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
