package io.github.bearisbug.synaxis.timer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class TaskImplTest {

    // Ids come from a global counter that wraps around, two live tasks can share one
    @Test
    public void tasksSharingAnIdStayDistinct() {
        final SchedulerImpl scheduler = new SchedulerImpl(_ -> {});
        final TaskImpl first = new TaskImpl(7, TaskSchedule::stop, ExecutionType.TICK_START, scheduler);
        final TaskImpl second = new TaskImpl(7, TaskSchedule::stop, ExecutionType.TICK_START, scheduler);
        assertNotEquals(first, second);

        final Map<Task, String> tasks = new HashMap<>();
        tasks.put(first, "first");
        tasks.put(second, "second");
        assertEquals(2, tasks.size());
        assertEquals("first", tasks.get(first));
    }
}
