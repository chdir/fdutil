package net.sf.fakenames.fddemo.icons;

public enum Icons implements IIcon {
    HOME {
        @Override
        public String getFormattedName() {
            return "{Home}";
        }

        @Override
        public String getName() {
            return "Home";
        }

        @Override
        public char getCharacter() {
            return '\uE88A';
        }

        @Override
        public ITypeface getTypeface() {
            return impl;
        }
    };

    private static final FontImpl impl = new FontImpl();
}
