## CongoCC Examples

This directory contains various examples which can actually be independently useful. 
  * The java directory gives an example of how to use the Java grammar that JavaCC itself uses.
  * The freemarker directory contains a grammar for FTL (FreeMarker Template Language) which is intended to eventually replace the crufty old grammar that FreeMarker currently uses! There is a separate FEL.ccc file (FEL being FreeMarker Expression Language) which could be separately useful for people in their own projects.
  * The JSON grammar is quite simple and can be *included* in your own grammar via the INCLUDE mechanism. Actually, you can see a simple INCLUDE in action by inspecting the JSONC.ccc grammar.

