package com.btk.jsf;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

public class ViewExpiredExceptionHandlerFactory extends ExceptionHandlerFactory {

    private final ExceptionHandlerFactory wrapped;

    public ViewExpiredExceptionHandlerFactory(ExceptionHandlerFactory wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new ViewExpiredExceptionHandler(wrapped.getExceptionHandler());
    }
}
