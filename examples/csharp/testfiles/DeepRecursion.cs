using System;

class A {
    static int Main ()
    {
        a = ((((((((((1)))))))))); 
            a = (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7)))))))))))))))))));
#if false			
            a = (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7) +
                (((((a + 1) - 2) + 3) - 4 + 5 - 6 + 7)))))))))))))))))));
#endif        
        // no ssa
        return a != (a + 1) ? 0 : 1;
    }
    
    static void Foo (out int dummy) { dummy = 0; }
}
