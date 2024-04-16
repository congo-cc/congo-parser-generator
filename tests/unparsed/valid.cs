#pragma warn disable 999

namespace foo.bar {
#if true
    /* This is a comment. */
    class Foo {}
#else
    // This is another.
    class Bar {}
#endif
}
