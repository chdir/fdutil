package net.sf.fakenames.fddemo;

import net.sf.fakenames.fddemo.provider.FileProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21, constants = BuildConfig.class, application = FdDemoApp.class)
public class CanonDetector {
    @Test
    public void testSimple0() {
        assertTrue(FileProvider.isCanon("/home/example"));
    }

    @Test
    public void testSimple1() {
        assertTrue(FileProvider.isCanon("/home/example/dir"));
    }

    @Test
    public void testSimple2() {
        assertTrue(FileProvider.isCanon("/home/example/dir/nested"));
    }

    @Test
    public void testSimple3() {
        assertTrue(FileProvider.isCanon("/home/example/dir/very/deeply/nested"));
    }

    @Test
    public void testSimpleDir0() {
        assertTrue(FileProvider.isCanon("/home/example/"));
    }

    @Test
    public void testSimpleDir1() {
        assertTrue(FileProvider.isCanon("/home/example/dir/"));
    }

    @Test
    public void testSimpleDir2() {
        assertTrue(FileProvider.isCanon("/home/example/dir/nested/"));
    }

    @Test
    public void testSimpleDir3() {
        assertTrue(FileProvider.isCanon("/home/example/dir/very/deeply/nested/"));
    }

    @Test
    public void testDups0() {
        assertFalse(FileProvider.isCanon("//home/example"));
    }

    @Test
    public void testDups1() {
        assertFalse(FileProvider.isCanon("/home//example"));
    }

    @Test
    public void testDups2() {
        assertFalse(FileProvider.isCanon("/home/example//"));
    }

    @Test
    public void testOneDot0() {
        assertFalse(FileProvider.isCanon("/./home/example"));
    }

    @Test
    public void testOneDot1() {
        assertFalse(FileProvider.isCanon("/home/./example"));
    }

    @Test
    public void testOneDot2() {
        assertFalse(FileProvider.isCanon("/home/example/."));
    }

    @Test
    public void testOneDotDir() {
        assertFalse(FileProvider.isCanon("/home/example/./"));
    }

    @Test
    public void testTwoDots0() {
        assertFalse(FileProvider.isCanon("/../home/example"));
    }

    @Test
    public void testTwoDots1() {
        assertFalse(FileProvider.isCanon("/home/../example"));
    }

    @Test
    public void testTwoDots2() {
        assertFalse(FileProvider.isCanon("/home/example/.."));
    }

    @Test
    public void testTwoDotsDir() {
        assertFalse(FileProvider.isCanon("/home/example/../"));
    }

    @Test
    public void testOneDotFalse0() {
        assertTrue(FileProvider.isCanon("/home/.example"));
    }

    @Test
    public void testOneDotFalse1() {
        assertTrue(FileProvider.isCanon("/home/example."));
    }

    @Test
    public void testOneDotFalse2() {
        assertTrue(FileProvider.isCanon("/.home/.example"));
    }

    @Test
    public void testOneDotFalse3() {
        assertTrue(FileProvider.isCanon("/home./example."));
    }

    @Test
    public void testTwoDotsFalse0() {
        assertTrue(FileProvider.isCanon("/..home/..example"));
    }

    @Test
    public void testTwoDotsFalse1() {
        assertTrue(FileProvider.isCanon("/home../example.."));
    }
}
