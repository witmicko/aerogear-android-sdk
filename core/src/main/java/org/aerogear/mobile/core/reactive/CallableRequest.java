package org.aerogear.mobile.core.reactive;

import static org.aerogear.mobile.core.utils.SanityCheck.nonNull;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import android.support.annotation.NonNull;
import android.util.Log;


/**
 * This class will synchronously invoke a callable parameter when
 * {@link CallableRequest#respondWith(Responder)} is invoked
 *
 * @param <T> a type that this request generates
 */
public final class CallableRequest<T> extends AbstractRequest<T> {

    private static final String ERROR_TAG = "UNCAUGHT_EXCEPTION";
    private final Callable<T> callable;
    private Thread callableThread = null;

    public CallableRequest(Callable<T> callable) {
        this.callable = nonNull(callable, "callable");
    }

    @Override
    public Request<T> respondWithActual(@NonNull AtomicReference<Responder<T>> responderRef) {

        if (responderRef.get() == null) { // responder may have been disconnected.
            return this;
        }

        T value = null;
        Exception exception = null;

        /*
         * The reason we calculate the exception and value parameters in one try block and then call
         * the responder in another is to isolate the calculation of the result from the handling of
         * the response.
         *
         * Otherwise it would be possible for the call to succeed but then the responder would throw
         * an exception in the onSuccess method that would be passed to the responder's onException.
         *
         */
        try {
            callableThread = Thread.currentThread();
            value = callable.call();
            synchronized (callable) { // We are synchronizing on the callable because we don't want
                                      // the thread reference to go null in the cancel method.
                callableThread = null;
            }
        } catch (Exception e) {
            exception = e;
        }

        Responder<T> responder = responderRef.get();
        if (responder == null) { // responder may have been disconnected while the calculation was
                                 // performed.
            return this;
        }

        try {
            if (exception == null) {
                responder.onResult(value);
            } else {
                responder.onException(exception);
            }
        } catch (Throwable responderThrowable) {
            if (responderThrowable instanceof Error) {
                /*
                 * Applications should not try to handle errors. If a responder threw an error this
                 * means that something fundamental has failed and it should be passed out of the
                 * Reactive system.
                 */
                throw responderThrowable;
            } else {
                /*
                 * Responders with uncaught exceptions should not blow up the reactive stack. For
                 * now we will log them, but one day a RxPlugin style mechanism may be appropriate.
                 */
                Log.e(ERROR_TAG, responderThrowable.getMessage(), responderThrowable);
            }
        }
        return this;
    }

    @Override
    public void cancel() {
        synchronized (callable) {
            if (callableThread != null) {
                callableThread.interrupt();
            }
        }
    }
}
