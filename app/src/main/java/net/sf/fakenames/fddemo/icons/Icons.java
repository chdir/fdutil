package net.sf.fakenames.fddemo.icons;

public enum Icons implements IIcon {
    PASTE {
        @Override
        public String getFormattedName() {
            return "{Paste}";
        }

        @Override
        public String getName() {
            return "Paste";
        }

        @Override
        public char getCharacter() {
            return '\uE14F';
        }

        @Override
        public ITypeface getTypeface() {
            return impl;
        }
    },

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
