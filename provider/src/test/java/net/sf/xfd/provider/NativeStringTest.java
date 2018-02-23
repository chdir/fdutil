package net.sf.xfd.provider;

import net.sf.xfd.NativeString;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static net.sf.xfd.provider.ProviderBase.isCanon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21, constants = BuildConfig.class)
public class NativeStringTest {
    @Test
    public void testRoot() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("/".getBytes());

        assertEquals(hasher.getFilename("/", false), expected);
    }

    @Test
    public void testRootChild() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("example".getBytes());

        assertEquals(hasher.getFilename("/example", false), expected);
    }

    @Test
    public void testRootChildB64() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("hi".getBytes());

        assertEquals(hasher.getFilename("/aGk", true), expected);
    }

    @Test
    public void testRootChildB64WithPadding() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("hi".getBytes());

        assertEquals(hasher.getFilename("/aGk=", true), expected);
    }

    @Test
    public void test2LvlB64() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("people".getBytes());

        assertEquals(hasher.getFilename("/aGk/cGVvcGxl", true), expected);
    }

    @Test
    public void test2LvlB64DirPath() {
        final PublicProvider.Hasher hasher = new PublicProvider.Hasher(RuntimeEnvironment.application);

        NativeString expected = new NativeString("/hi".getBytes());

        assertEquals(hasher.getParent("/aGk/cGVvcGxl", true), expected);
    }
}
