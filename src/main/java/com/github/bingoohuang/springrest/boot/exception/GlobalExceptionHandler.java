package com.github.bingoohuang.springrest.boot.exception;

import com.github.bingoohuang.utils.net.Http;
import com.github.bingoohuang.utils.net.Url;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NotFoundException.class)
    public void handleConflict(NotFoundException ex, HttpServletResponse response) {
        respondText(response, ex.getMessage());
    }

    @ExceptionHandler(RestException.class)
    public void handleConflict(RestException ex, HttpServletResponse response) {
        response.setStatus(ex.getHttpStatusCode());
        respondText(response, ex.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    public void handleConflict(Throwable ex, HttpServletResponse response) {
        response.setStatus(500);
        respondText(response, ex.getMessage());
    }

    public static void respondText(HttpServletResponse rsp, String text) {
        try {
            rsp.setHeader("Content-Type", "text/plain; charset=UTF-8");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();
            writer.write(text);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}