package com.btk.jsf;

import java.io.IOException;
import java.util.Iterator;

import jakarta.faces.FacesException;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.PartialViewContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;

public class ViewExpiredExceptionHandler extends ExceptionHandlerWrapper {

    private final ExceptionHandler wrapped;

    public ViewExpiredExceptionHandler(ExceptionHandler wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public ExceptionHandler getWrapped() {
        return wrapped;
    }

    @Override
    public void handle() throws FacesException {
        Iterator<ExceptionQueuedEvent> unhandled = getUnhandledExceptionQueuedEvents().iterator();

        while (unhandled.hasNext()) {
            ExceptionQueuedEvent event = unhandled.next();
            Throwable throwable = ((ExceptionQueuedEventContext) event.getSource()).getException();

            if (!isViewExpired(throwable)) {
                continue;
            }

            FacesContext facesContext = FacesContext.getCurrentInstance();
            ExternalContext externalContext = facesContext.getExternalContext();
            String loginUrl = externalContext.getRequestContextPath() + "/login.xhtml";

            try {
                PartialViewContext partial = facesContext.getPartialViewContext();
                if (partial != null && partial.isAjaxRequest()) {
                    String encoded = externalContext.encodeRedirectURL(loginUrl, null);
                    facesContext.getPartialViewContext().getEvalScripts()
                            .add("window.location='" + escapeJs(encoded) + "';");
                    facesContext.responseComplete();
                } else {
                    externalContext.redirect(loginUrl);
                    facesContext.responseComplete();
                }
            } catch (IOException e) {
                throw new FacesException(e);
            } finally {
                unhandled.remove();
            }
        }

        wrapped.handle();
    }

    private boolean isViewExpired(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ViewExpiredException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String escapeJs(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
