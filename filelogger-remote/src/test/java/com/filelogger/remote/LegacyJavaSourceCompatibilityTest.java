package com.filelogger.remote;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class LegacyJavaSourceCompatibilityTest {
    @Test
    public void rc6JavaConstructorsRemainSourceCompatible() {
        byte[] payload = new byte[] { 1, 2, 3 };
        RemoteLogBatch batch = new RemoteLogBatch(
            payload,
            "application/x-ndjson",
            null
        );
        RemoteLogUploader uploader = ignored -> true;
        RemoteLogDestination destination = new RemoteLogDestination(
            uploader,
            new RemoteLogConfig(new File("build/java-compat-spool"), 1, 4, 4, 0, 0, false)
        );

        assertEquals(payload, batch.getPayload());
        assertNull(batch.getContentEncoding());
        destination.close(2_000);
    }
}
