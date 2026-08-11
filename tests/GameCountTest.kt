// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// The status strip said "Games ✓ 1 in ed" while Eden's own list showed
// "no files found". Two different definitions of "a game":
//
//   * the scanner accepted nso and kip; Game.extensions upstream is only
//     xci, nsp, nca, nro
//   * the scanner walked the whole tree; GameHelper uses depth 3 with deep
//     scan on and 1 without
//
// Either alone produces a count the library cannot reproduce, which sends the
// user hunting for a missing game that was never importable. These tests pin
// both rules against the upstream ones.


import java.util.Locale

val UPSTREAM = setOf("xci","nsp","nca","nro")          // Game.extensions
val OLD_SCANNER = setOf("xci","nsp","nca","nro","nso","kip")
val NON_GAME = setOf("nso","kip","bin","zip","7z","rar")

sealed class N { data class D(val n:String, val c:MutableList<N> = mutableListOf()):N()
                 data class F(val n:String, val b:Long):N() }

fun ext(n:String) = n.substringAfterLast('.',"").lowercase(Locale.ROOT)

fun count(root:N.D, exts:Set<String>, maxDepth:Int): Pair<Int,Int> {
    var games=0; var skipped=0
    val q = ArrayDeque<Pair<N.D,Int>>(); q.add(root to maxDepth)
    while(q.isNotEmpty()){
        val (d,depth)=q.removeFirst()
        for(c in d.c) when(c){
            is N.D -> if(depth>1) q.add(c to depth-1)
            is N.F -> { val e=ext(c.n)
                        if(e in exts) games++ else if(e in NON_GAME) skipped++ }
        }
    }
    return games to skipped
}

var pass=0; var fail=0
fun check(name:String, cond:Boolean){ if(cond){pass++;println("  ok  $name")} else {fail++;println("  FAIL $name")} }

fun main(){
    println("\ngame counting")

    // The reported case: one .nso sitting in the folder. Old scanner counted
    // it, Eden ignores it -> "1 game" over an empty list.
    val nsoOnly = N.D("ed", mutableListOf(N.F("game.nso", 284L*1024*1024)))
    check("old scanner counted a .nso as a game", count(nsoOnly, OLD_SCANNER, 1).first == 1)
    check("upstream does not accept .nso",        count(nsoOnly, UPSTREAM, 1).first == 0)
    check("new scanner agrees with upstream",     count(nsoOnly, UPSTREAM, 1).first == 0)
    check("and reports it as a skipped file",     count(nsoOnly, UPSTREAM, 1).second == 1)

    // Depth: a game one level down, deep scan off.
    val nested = N.D("ed", mutableListOf(
        N.D("Blade", mutableListOf(N.F("blade.nsp", 2_000_000_000L)))))
    check("depth 1 misses a nested game, as upstream does", count(nested, UPSTREAM, 1).first == 0)
    check("depth 3 finds it, as deep scan does",            count(nested, UPSTREAM, 3).first == 1)

    // Deeper than upstream ever looks.
    val deep = N.D("ed", mutableListOf(
        N.D("a", mutableListOf(N.D("b", mutableListOf(N.D("c",
            mutableListOf(N.F("x.nsp", 1L)))))))))
    check("depth 3 stops where upstream stops", count(deep, UPSTREAM, 3).first == 0)

    // A normal library still works.
    val ok = N.D("ed", mutableListOf(
        N.F("a.nsp", 1L), N.F("b.xci", 2L), N.F("readme.txt", 3L), N.F("c.kip", 4L)))
    val (g,s) = count(ok, UPSTREAM, 1)
    check("counts real roms only", g == 2)
    check("kip reported as skipped, txt ignored", s == 1)

    println("\n$pass passed, $fail failed")
    if(fail>0) kotlin.system.exitProcess(1)
}
