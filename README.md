| Branch | Build Status                                        |
| ------ |:----------------------------------------------------|
| Master | [![Master Build status][master build]][travis link] |

[travis link]: https://travis-ci.org/chdir/fdutil
[master build]: https://travis-ci.org/chdir/fdutil.svg?branch=master

### Usage

[![Download](https://api.bintray.com/packages/alexanderr/maven/super-providers/images/download.svg)](https://bintray.com/alexanderr/maven/super-providers/_latestVersion)

Add the library to project:

```groovy
repositories {
    maven {
        url 'http://dl.bintray.com/alexanderr/maven'
    }
}

dependencies {
  implementation 'net.sf.xfd:providers:0.1'
}
```

The library includes two ContentProviders: one is exported without permission protection
(no extra steps like calling grantUriPermission required!), and security is ensured by signing
dynamically generated Uri with SHA1-based HMAC. Annother one implements DocumentProvider contract
(an entry in file choser). You can disable either of them, if you don't need both.

See [source code of example project][1] for basic usage.

[1]: https://github.com/chdir/fdutil/blob/f972290ef1ddb60f07cf7/app/src/main/java/net/sf/fakenames/fddemo/MainActivity.java#L366
