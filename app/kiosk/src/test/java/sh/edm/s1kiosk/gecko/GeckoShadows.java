package sh.edm.s1kiosk.gecko;

import android.content.Context;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Robolectric shadows for the GeckoView calls the app makes. Gecko itself never starts: each call is
 * written to {@link #events} as a line, so a test can compare what the app asked Gecko to do.
 * Sessions are numbered in the order they open (s1, s2, …).
 */
public final class GeckoShadows {
    private GeckoShadows() {}

    public static final List<String> events = new ArrayList<>();
    public static final List<GeckoSession> sessions = new ArrayList<>();
    public static GeckoRuntimeSettings runtimeSettings;
    public static WebExtension.MessageDelegate messageDelegate;

    private static GeckoRuntime runtime;

    public static void reset() {
        events.clear();
        sessions.clear();
        runtimeSettings = null;
        messageDelegate = null;
        runtime = null;
        setStatic(GeckoRuntime.class, "sRuntime", null);
    }

    static String name(GeckoSession s) {
        int i = sessions.indexOf(s);
        return i < 0 ? "s?" : "s" + (i + 1);
    }

    /** An instance made without running a constructor, for GeckoView types the app only passes along. */
    @SuppressWarnings("unchecked")
    public static <T> T allocate(Class<T> c) {
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            Field f = u.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (T) u.getMethod("allocateInstance", Class.class).invoke(f.get(null), c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setStatic(Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sets a (final) instance field declared on {@code owner}. */
    public static void setField(Object target, Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Implements(GeckoRuntime.class)
    public static class ShadowRuntime {
        @Implementation
        protected static GeckoRuntime create(Context context, GeckoRuntimeSettings settings) {
            if (runtime != null) throw new IllegalStateException("Only one GeckoRuntime instance is allowed");
            events.add("runtime.create");
            runtimeSettings = settings;
            runtime = org.robolectric.util.ReflectionHelpers.callConstructor(GeckoRuntime.class);
            return runtime;
        }
    }

    @Implements(WebExtensionController.class)
    public static class ShadowExtensionController {
        @Implementation
        protected GeckoResult<WebExtension> ensureBuiltIn(String uri, String id) {
            events.add("extension " + uri + " " + id);
            return GeckoResult.fromValue(allocate(WebExtension.class));
        }
    }

    @Implements(WebExtension.SessionController.class)
    public static class ShadowSessionController {
        @Implementation
        protected void setMessageDelegate(WebExtension ext, WebExtension.MessageDelegate delegate, String nativeApp) {
            events.add("messageDelegate " + nativeApp);
            messageDelegate = delegate;
        }
    }

    @Implements(GeckoSession.class)
    public static class ShadowSession {
        @RealObject GeckoSession real;
        private boolean open;

        @Implementation
        protected void open(GeckoRuntime r) {
            sessions.add(real);
            open = true;
            events.add(name(real) + ".open");
        }

        @Implementation
        protected boolean isOpen() { return open; }

        @Implementation
        protected void close() {
            open = false;
            events.add(name(real) + ".close");
        }

        @Implementation
        protected void loadUri(String uri) { events.add(name(real) + ".load " + uri); }

        @Implementation
        protected void reload() { events.add(name(real) + ".reload"); }

        @Implementation
        protected void goBack() { events.add(name(real) + ".goBack"); }
    }

    @Implements(GeckoView.class)
    public static class ShadowView extends org.robolectric.shadows.ShadowViewGroup {
        private GeckoSession session;

        @Implementation
        protected void setSession(GeckoSession s) {
            session = s;
            events.add("view.setSession " + name(s));
        }

        @Implementation
        protected GeckoSession releaseSession() {
            GeckoSession s = session;
            session = null;
            events.add("view.releaseSession");
            return s;
        }

        @Implementation
        protected GeckoSession getSession() { return session; }

        @Implementation
        protected void coverUntilFirstPaint(int color) {
            events.add(String.format("view.cover #%08x", color));
        }
    }

    /** The sender of a port: a page URL, top level or a frame. */
    public static WebExtension.MessageSender sender(String url, boolean topLevel) {
        WebExtension.MessageSender s = allocate(WebExtension.MessageSender.class);
        setField(s, WebExtension.MessageSender.class, "url", url);
        setField(s, WebExtension.MessageSender.class, "isTopLevel", topLevel);
        return s;
    }

    /** A native port as content.js opens it, which records what the app posts to it. */
    public static class FakePort extends WebExtension.Port {
        public final List<JSONObject> posted = new ArrayList<>();
        public WebExtension.PortDelegate delegate;

        public FakePort(String url, boolean topLevel) {
            super();
            setField(this, WebExtension.Port.class, "sender", sender(url, topLevel));
        }

        @Override
        public void postMessage(JSONObject message) {
            try {
                posted.add(new JSONObject(message.toString()));
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void setDelegate(WebExtension.PortDelegate d) { delegate = d; }
    }
}
