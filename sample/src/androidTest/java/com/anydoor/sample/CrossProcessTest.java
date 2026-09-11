package com.anydoor.sample;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import com.anydoor.AnyDoor;
import com.anydoor.CallHandler;
import com.anydoor.CallResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 创建时间：2026-09-11；创建人：Codex。 */
@RunWith(AndroidJUnit4.class)
public class CrossProcessTest {
    @Test
    public void testSyncAndAsyncCallsAcrossProcesses() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AnyDoor.initialize(context);
        CountDownLatch resultLatch = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicInteger code = new AtomicInteger(-1);
        CallHandler echo = (id, arg) -> CallResult.doneWith("任意门：" + arg);
        AnyDoor.registerHandler("sample.echo", echo);
        try {
            ResultReceiver receiver = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int resultCode, Bundle data) {
                    code.set(resultCode);
                    result.set(data);
                    resultLatch.countDown();
                }
            };
            context.startService(new Intent(context, RemoteService.class)
                .putExtra("receiver", receiver).putExtra("input", "测试"));
            assertTrue("远程进程响应超时", resultLatch.await(15, TimeUnit.SECONDS));
            assertEquals(String.valueOf(result.get()), 0, code.get());
            assertTrue(result.get().getInt("remotePid") != Process.myPid());
            assertEquals("测试", result.get().getString("input"));
            assertEquals("任意门：测试", result.get().getString("syncResult"));
            assertEquals("任意门：测试", result.get().getString("asyncResult"));
            AnyDoor.unregisterHandler("sample.echo", echo);
            assertNull(AnyDoor.call("sample.echo", "已注销"));
        } finally {
            AnyDoor.unregisterHandler("sample.echo", echo);
            context.stopService(new Intent(context, RemoteService.class));
        }
    }
}
