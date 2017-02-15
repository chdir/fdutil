package net.sf.xfd;

final class Util {
    private Util() {}

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T conceal(Exception ex) {
        return (T) ex;
    }

    static void sneakyThrow(Exception ex) {
        throw Util.<RuntimeException>conceal(ex);
    }
}
