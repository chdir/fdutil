package net.sf.xfd.provider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static net.sf.xfd.provider.ProviderBase.removeDotSegments;
import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21, constants = BuildConfig.class)
public class ExampleUnitTest {
    @Test
    public void garbageAtStart() throws Exception {
        String badPath = "/./.././././fstab.zram";

        final StringBuilder sb = new StringBuilder(badPath);

        removeDotSegments(sb);

        assertTrue("/fstab.zram".contentEquals(sb));
    }

    @Test
    public void garbageAtEnd0() throws Exception {
        String proc = "/..";

        final StringBuilder sb = new StringBuilder(proc);

        removeDotSegments(sb);

        assertTrue("/".contentEquals(sb));
    }

    @Test
    public void garbageAtEnd1() throws Exception {
        String proc = "/.";

        final StringBuilder sb = new StringBuilder(proc);

        removeDotSegments(sb);

        assertTrue("/".contentEquals(sb));
    }

    @Test
    public void garbageAtEnd2() throws Exception {
        String proc = "/proc/self/net/.././../";

        final StringBuilder sb = new StringBuilder(proc);

        removeDotSegments(sb);

        assertTrue("/proc/".contentEquals(sb));
    }

    @Test
    public void garbageAtEnd3() throws Exception {
        String proc = "/proc/self/net/.././..";

        final StringBuilder sb = new StringBuilder(proc);

        removeDotSegments(sb);

        assertTrue("/proc/".contentEquals(sb));
    }

    @Test
    public void ascend1() throws Exception {
        String badPath = "/proc/self/../vmstat";

        final StringBuilder sb = new StringBuilder(badPath);

        removeDotSegments(sb);

        assertTrue("/proc/vmstat".contentEquals(sb));
    }

    @Test
    public void ascend2() throws Exception {
        String badPath = "/proc/self/task/..";

        final StringBuilder sb = new StringBuilder(badPath);

        removeDotSegments(sb);

        assertTrue("/proc/self/".contentEquals(sb));
    }

    @Test
    public void longAscend() throws Exception {
        String badPath = "/proc/self/cwd/../task/../../tty/driver";

        final StringBuilder sb = new StringBuilder(badPath);

        removeDotSegments(sb);

        assertTrue("/proc/tty/driver".contentEquals(sb));
    }
}