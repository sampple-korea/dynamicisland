package dev.android.dynamicisland.island;

import static org.junit.Assert.*;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.island.window.IslandWindow;
import dev.android.dynamicisland.settings.ModuleSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, shadows=ReplyFailureTest.WindowShadow.class)
public class ReplyFailureTest {
    @Implements(value=IslandWindow.class,isInAndroidSdk=false)
    public static class WindowShadow {
        static IslandWindow.ReplyListener listener;
        static boolean open;
        static CharSequence error;
        @Implementation protected void __constructor__(Context ctx,
                dev.android.dynamicisland.island.view.IslandHost host,IslandWindow.Listener callback) { }
        @Implementation protected boolean showReply(CharSequence hint,CharSequence send,IslandWindow.ReplyListener callback) {
            listener=callback;open=true;return true;
        }
        @Implementation protected void hideReply() { open=false; }
        @Implementation protected void replyFailed(CharSequence message) { error=message; }
    }
    @Test public void aFailedReplyMustKeepTheEditorAndDraftAvailable() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        ModuleSettings.configurePreview(ModuleSettings.openStore(app));
        IslandView view=new IslandView(app,null);
        IslandWindow window=new IslandWindow(app,null,null);
        IslandController controller=ReflectionHelpers.callConstructor(IslandController.class,
                ReflectionHelpers.ClassParameter.from(Context.class,app),
                ReflectionHelpers.ClassParameter.from(ClassLoader.class,getClass().getClassLoader()));
        IslandPlacement placement=ReflectionHelpers.getField(controller,"placement");
        ReflectionHelpers.setField(placement,"view",view);ReflectionHelpers.setField(placement,"window",window);
        ItemLauncher launcher=ReflectionHelpers.getField(controller,"launcher");
        PendingIntent intent=PendingIntent.getBroadcast(app,0,new Intent("audit.cancelled.reply").setPackage(app.getPackageName()),PendingIntent.FLAG_MUTABLE);
        IslandItem.Action action=new IslandItem.Action("Reply",null,intent,null,false);
        action.remoteInputs=new RemoteInput[]{new RemoteInput.Builder("reply").build()};
        IslandItem item=new IslandItem("peek:reply");item.title="Audit";
        boolean shown=ReflectionHelpers.callInstanceMethod(controller,"startReply",
                ReflectionHelpers.ClassParameter.from(IslandItem.class,item),
                ReflectionHelpers.ClassParameter.from(IslandItem.Action.class,action));
        assertTrue(shown);assertTrue(WindowShadow.open);assertNotNull(WindowShadow.listener);
        intent.cancel();
        assertFalse("precondition: the production launcher reports the cancelled intent as a failure",launcher.sendReply(action,"unsent draft"));
        WindowShadow.listener.onSend("unsent draft");
        assertTrue("the production callback must not discard the reply editor after a failed send",WindowShadow.open);
        assertNotNull("the failed send has a visible explanation",WindowShadow.error);
        Object scene=ReflectionHelpers.getField(view,"scene");
        assertEquals(Boolean.TRUE,ReflectionHelpers.getField(scene,"held"));
        WindowShadow.listener.onDismiss();assertFalse(WindowShadow.open);
        assertEquals(Boolean.FALSE,ReflectionHelpers.getField(scene,"held"));
    }
}
