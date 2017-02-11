#!/bin/bash -x

if [ `getconf LONG_BIT` = "64" ]
then
    ARCH=amd64
    FSARCH=x86_64
else
    ARCH=i386
    FSARCH=$ARCH
fi

wget --progress=dot http://security.ubuntu.com/ubuntu/pool/main/g/gcc-5/libstdc++6_5.4.0-6ubuntu1~16.04.4_$ARCH.deb -O libstdc.deb
wget --progress=dot http://lug.mtu.edu/ubuntu/pool/main/g/gccgo-6/libgcc1_6.0.1-0ubuntu1_$ARCH.deb -O libgcc.deb
wget --progress=dot http://lug.mtu.edu/ubuntu/pool/main/g/glibc/libc6_2.23-0ubuntu3_$ARCH.deb -O libc.deb

mkdir tmp
mkdir cmake_deps

dpkg -x libstdc.deb tmp
dpkg -x libgcc.deb tmp
dpkg -x libc.deb tmp

for i in tmp/{,usr/}lib/$FSARCH-linux-gnu/*; do
    ln -sf "$(readlink -f $i)" cmake_deps/
done

ln -sf libstdc++.so.6.0.21 cmake_deps/libstdc++.so.6
ln -sf libm-2.23.so cmake_deps/libm.so.6
ln -sf libdl-2.23.so cmake_deps/libdl.so.2
ln -sf libc-2.23.so cmake_deps/libc.so.6

CMAKE_DIR="$ANDROID_HOME/cmake/3.6.3155560/bin/"

mv "$CMAKE_DIR/cmake"{,.exe.bak}

tee "$CMAKE_DIR/cmake"  <<EOF
#!/bin/bash
SELF=\`dirname "\$(readlink -f "\$0")"\`

DEPS="$(readlink -f cmake_deps/)"

"\$DEPS"/ld-2.23.so --inhibit-rpath "" --inhibit-cache --library-path "\$DEPS" "\$SELF"/cmake.exe.bak "\$@"
EOF

chmod 755 "$CMAKE_DIR/cmake"
