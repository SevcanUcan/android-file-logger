import com.filelogger.remote.RemoteLogBatch;
import com.filelogger.remote.RemoteLogConfig;
import com.filelogger.remote.RemoteLogDestination;
import com.filelogger.remote.RemoteLogUploader;

import java.io.File;

public final class LegacyRemoteBinaryConsumer {
    public static void main(String[] args) {
        RemoteLogBatch batch = new RemoteLogBatch(
            new byte[] { 1, 2, 3 },
            "application/x-ndjson",
            null
        );
        RemoteLogUploader uploader = ignored -> true;
        RemoteLogDestination destination = new RemoteLogDestination(
            uploader,
            new RemoteLogConfig(new File(args[0]), 1, 4, 4, 0, 0L, false)
        );
        if (batch.getPayload().length != 3 || batch.getContentEncoding() != null) {
            throw new AssertionError("Legacy RemoteLogBatch contract changed");
        }
        if (!destination.close(2_000L)) {
            throw new AssertionError("Legacy destination failed to close");
        }
    }
}
