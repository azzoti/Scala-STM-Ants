Scala Ant Colony Simulation based on Ant colony simulation in Clojure
=====================================================================

This project is a port to Scala 2.11 of the 2009 [Ant colony simulation in Clojure][clojure-ants-video] from a [talk by Rich Hickey][clojure-ants-talk] : original clojure source [here][ants.clj.1] or [here][ants.clj.2].
(According to this [thread][clojure-ants-dated], the clojure code is rather dated so a version updated to clojure 1.4 is suggested [here][ants.clj.3]).

Features of the clojure ants simulation:

- The clojure demo demonstrates the use of Clojure "agents" with one agent per ant accessing a shared world state via Clojure refs using the clojure STM.
- The world is made up of Cells where each cell is a clojure Ref.
- The ants attempt to operate on the world of cells, relying on the clojure STM to rollback or commit conflicts with other ants.
- There is a clojure "animator" agent that takes a consistent snapshot of the world in order to display it.
- There is a clojure "evaporator" agent that evaporates the pheromones left by the foraging ants.
- The code is *implicitly* multi threaded and in principle can use all available processors to access shared state using clojure's [controlled STM semantics][clojure-concurrent_programming].

Features of this scala version:

- It uses the [Scala STM][scala-stm] for Refs and and [Akka actors][akka] instead of clojure agents.
- It follows the clojure implementation very closely.
- It  uses the Scala STM "Ref" class for managing shared state - this is a direct analog of Clojure "Ref" type.
- Scala does not have a direct equivalent of Clojure agents, so Akka "Actors" are used in a similar way to the clojure agents.
  (This is _not_ an idiomatic usage of Akka actors. Conceptually "Actors" are intended never to share state and only communicate using message passing.)
- The code is *implicitly* multi threaded and uses all available processors. (This can be seen if you set the ant sleep time to zero on startup).
- On startup, the application allows you to select the number of ants to use, the ant sleep time, and whether the animator should "stop-the-world" to get a consistent world view.
- For reasons I haven't tried to understand, Akka's default fork-join-executor actor dispatcher does not allow the "animator" actor any processing time,
  when more than 16 ants are used. So to make it work for any number of ants, the default dispatcher has bee set to use a "thread-pool-executor" and everything works beautifully.


Credits
-------

Rich Hickey
(Credit also to to Peter Vlugter for the [original port][peter-ants] in 2009 to Scala 2.8)



Requirements
------------

To build and run you need [sbt Simple Build Tool][sbt]



Running
-------

Fetch this project with

    git clone https://github.com/azzoti/Scala-STM-Ants.git
    cd Scala-STM-Ants

To run Ants use "sbt run":

    sbt run


Notice
------

Project based on the Clojure ants simulation by Rich Hickey.

Copyright (c) Rich Hickey. All rights reserved.
The use and distribution terms for this software are covered by the
Common Public License 1.0 ([http://opensource.org/licenses/cpl1.0.php][cpl])
which can be found in the file cpl.txt at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.

[cpl]: http://opensource.org/licenses/cpl1.0.php
[sbt]: http://www.scala-sbt.org/
[clojure-ants-video]:https://www.youtube.com/watch?v=shm7QcJMvig
[clojure-ants-talk]:http://youtu.be/dGVqrGmwOAw
[ants.clj.1]:https://www.refheap.com/3096
[ants.clj.2]:http://www.lisptoronto.org/past-meetings/2009-05-clojure-ants-demo/ants.clj?attredirects=0&d=1
[clojure-ants-dated]:http://grokbase.com/t/gg/clojure/125x7j5mg5/is-still-idiomatic-the-ant-simulation-code
[ants.clj.3]:https://www.refheap.com/3099
[peter-ants]:http://github.com/pvlugter/ants
[ants]:http://grokbase.com/t/gg/clojure/125x7j5mg5/is-still-idiomatic-the-ant-simulation-code
[scala-stm]:http://nbronson.github.io/scala-stm/
[akka]:http://akka.io/
[clojure-concurrent_programming]:http://clojure.org/concurrent_programming

