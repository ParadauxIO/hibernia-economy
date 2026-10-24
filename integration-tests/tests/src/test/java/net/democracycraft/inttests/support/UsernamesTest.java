package net.democracycraft.inttests.support;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {
    @Test
    void rapidCallsProduceDistinctValidPlayerIdentities() {
        var names = IntStream.range(0, 5000).mapToObj(i -> Usernames.fresh("Name")).toList();
        assertThat(names).doesNotHaveDuplicates().allMatch(name -> name.matches("[A-Za-z0-9_]{3,16}"));
    }

    @Test
    void concurrentCallsProduceDistinctPlayerIdentities() {
        var names = IntStream.range(0, 5000).parallel().mapToObj(i -> Usernames.fresh("Name")).toList();
        assertThat(names).doesNotHaveDuplicates();
    }
}
