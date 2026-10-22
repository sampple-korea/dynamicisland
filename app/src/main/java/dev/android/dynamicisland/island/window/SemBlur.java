package dev.android.dynamicisland.island.window;

import android.view.View;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflective access to Samsung's hidden {@code SemBlurInfo} API. The lookup happens once; on any
 * other device (or after a failed call) {@link #isSupported()} stays false for good.
 */
final class SemBlur {
    /** The resolved reflection handles. */
    private static final class Api {
        final Constructor<?> newBuilder;
        final Method setRadius;
        final Method setCornerRadius;
        final Method setColor;
        final Method build;
        final Method applyToView;

        Api() throws ReflectiveOperationException {
            Class<?> builderClass = Class.forName("android.view.SemBlurInfo$Builder");
            Class<?> infoClass = Class.forName("android.view.SemBlurInfo");
            newBuilder = builderClass.getConstructor(Integer.TYPE);
            setRadius = builderClass.getMethod("setRadius", Integer.TYPE);
            setCornerRadius = builderClass.getMethod("setBackgroundCornerRadius", Float.TYPE);
            setColor = builderClass.getMethod("setBackgroundColor", Integer.TYPE);
            build = builderClass.getMethod("build");
            applyToView = View.class.getMethod("semSetBlurInfo", infoClass);
        }
    }

    private static Api api;
    private static boolean probed;

    private SemBlur() {
    }

    static boolean isSupported() {
        if (!probed) {
            probed = true;
            try {
                api = new Api();
            } catch (Throwable t) {
                XLog.e("SemBlurInfo unavailable", t);
                api = null;
            }
        }
        return api != null;
    }

    /** Gives {@code target} a background blur of the given radius, corner radius and tint colour. */
    static void apply(View target, int radius, float corner, int tint) throws ReflectiveOperationException {
        Object builder = api.newBuilder.newInstance(0);
        api.setRadius.invoke(builder, radius);
        api.setCornerRadius.invoke(builder, corner);
        api.setColor.invoke(builder, tint);
        api.applyToView.invoke(target, api.build.invoke(builder));
    }

    /** Removes the blur from {@code target}; failures are ignored (the view is going away). */
    static void clear(View target) {
        try {
            api.applyToView.invoke(target, (Object) null);
        } catch (Throwable ignored) {
            // nothing to undo
        }
    }

    /** Disables blur for the rest of the process after an unexpected failure. */
    static void giveUp() {
        api = null;
    }
}
