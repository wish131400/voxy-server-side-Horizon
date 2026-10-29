package dev.xantha.vss.compat;

import java.util.concurrent.ConcurrentLinkedDeque;

/** Executable shape of the fork that shares synchronized submit with ordinary local ingestion. */
public class IngestDirectQueueFixture {
    public final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();
    public boolean live = true;
    public int refs;
    public int processed;
    public record IngestSection(Object section) { }

    public boolean rawIngest0(Object section) { return submit(new IngestSection(section)); }

    public synchronized boolean submit(IngestSection task) {
        if (!live) return false;
        refs++;
        ingestQueue.add(task);
        return true;
    }

    public void processJob() {
        IngestSection task = ingestQueue.pop();
        Object section = task.section;
        try {
            if (section instanceof RuntimeException failure) throw failure;
            processed++;
        } finally { refs--; }
        VoxyIngestTracker.workerDone();
    }
}
