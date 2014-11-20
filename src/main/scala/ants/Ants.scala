package ants
import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.annotation.tailrec
import scala.collection.immutable.IndexedSeq
import scala.concurrent.stm.{Ref, _}
import scala.math.{max, min}
import scala.util.Random.{nextInt => randomInt}

case object Ping

class Ants(NantsSqrt: Int, AntSleepMilliseconds: Int, ConsistentWorldSnapshot: Boolean) {

  val NormalSize = NantsSqrt <= 40
  val Dim = if (NormalSize) 80 else max(min(NantsSqrt * 12, 160), 80) // dimensions of square world
  val HomeOff = if (NormalSize) Dim / 4 else Dim / 10
  val HomeSize = max(7, NantsSqrt)
  val FoodPlaces = if (NormalSize) 70 else NantsSqrt * 10 // number of places with food
  val FoodRange = 100 // range of amount of food at a place
  val FoodScale = 30.0 // scale factor for food drawing
  val PherScale = 20 // scale factor for pheromone drawing
  val EvapMillis = 100 // how often pheromone evaporation occurs (milliseconds)
  val fudge : Float = if (AntSleepMilliseconds==0) 0.05f else AntSleepMilliseconds.toFloat
  val EvapRate = 0.999f - ((40 / fudge) - 1) / 1000.0f // pheromone evaporation rate
  val AnimationSleepMs = 100 // 10 frames per second
  val AntMillis = AntSleepMilliseconds // ant pause

  // We could have also used akka HashTries instead of a Scala case class which 
  // would be a more direct translation of the clojure implementation 
  case class Ant(dir: Int, food: Boolean = false) {
    def turn(amt: Int) = copy(dir = Util.bound(8, dir + amt))
    def turnAround() = turn(4)
  }

  case class Cell(food: Int = 0, pher: Float = 0, ant: Option[Ant] = None, home: Boolean = false) {
    def occupied = ant.isDefined
    def withPher(f: (Float) => Float) = copy(pher = f(pher))
    def withAnt(newant: Ant) = copy(ant = Some(newant))
    def withoutAnt = copy(ant = None)
    def withFood(f: (Int) => Int) = copy(food = f(food))
  }

  class CellRef {
    val cell: Ref[Some[Cell]] = Ref(Some(Cell()))
    def cellValue = cell.single.get.get // atomic { implicit txn =>  theCell().get }
    def ant = cellValue.ant.get
    def transformCell(f: Cell => Cell) = cell.single.transformAndGet { someCell => Some(f(someCell.get)) }
    def addAnt(ant: Ant) = transformCell(_.withAnt(ant))
    def changeAnt(f: Ant => Ant) = transformCell(cell => cell.withAnt(f(cell.ant.get)))
    def removeAnt(): Some[Cell] = transformCell(_.withoutAnt)
    def changeFood(f: Int => Int) = transformCell(_.withFood(f))
    def makeHome = transformCell(_.copy(home = true))
    def occupied = cellValue.occupied
    def pher = cellValue.pher
    def pher(f: Float => Float) = transformCell(_.withPher(f))
    def pherIfNonZero(f: Float => Float) = atomic { implicit txn =>
      if (cell().get.pher > 0) transformCell(_.withPher(f)) else cell.single.get
    }
    def food = cellValue.food
    def home = cellValue.home
  }

  object World {

    val system = ActorSystem("ScalaAkkaAntsAfterClojureAnts")

    // places initial food and ants, returns seq of ActorRef
    def setup: Seq[ActorRef] = {
      for (i <- 1 to FoodPlaces) {
        World(randomInt(Dim), randomInt(Dim)) changeFood (oldFood => randomInt(FoodRange))
      }
      val homeRange = HomeOff until (HomeSize + HomeOff)
      for (x <- homeRange; y <- homeRange) {
        World(x, y).makeHome
      }
      val antsHomeRange = HomeOff until (NantsSqrt + HomeOff)
      val antActorRefs = for (x <- antsHomeRange; y <- antsHomeRange) yield {
        World(x, y).addAnt(Ant(randomInt(8)))
        system.actorOf(Props(new AntBehaviour(x, y)), s"AntBehaviour-$x-$y")
      }
      antActorRefs
    }
    //
    private lazy val ants = setup

    //private lazy val animator = system.actorOf(Props(new Animator).withDispatcher("akka.actor.animator-dispatcher"), "Animator")
    private lazy val animator = system.actorOf(Props(new Animator), "Animator")
    private lazy val evaporator = system.actorOf(Props(new Evaporator), "Evaporator")
    private lazy val world = Array.fill(Dim, Dim)(new CellRef)
    //
    def apply(loc: (Int, Int)) = world(loc._1)(loc._2)
    def snapshot = {
      val ss = if (ConsistentWorldSnapshot) {
        atomic { implicit txn => for (x <- 0 until Dim; y <- 0 until Dim) yield World(x, y).cell }
      } else {
        for (x <- 0 until Dim; y <- 0 until Dim) yield atomic { implicit txn => World(x, y).cell }
      }
      ss
    }

    def start = {

      import scala.concurrent.duration._
      // Start the animator and the pheromone evaporator
      system.scheduler.schedule(1.seconds, AnimationSleepMs.milliseconds, animator, Ping)(system.dispatcher, animator)
      system.scheduler.schedule(1.seconds, EvapMillis.milliseconds, evaporator , Ping)(system.dispatcher, evaporator)

      // Ping each ant to get it started
      ants foreach (_ ! Ping)

    }
  }

  object Util {

    // returns a map of xs to their 1-based rank when sorted by f
    def rankBy[A, B: Ordering](f: A => B, xs: Seq[A]) = xs.sortBy(f).zip(Stream from 1).toMap

    // returns n wrapped into range 0-b
    def bound(b: Int, n1: Int) = {
      val n = n1 % b
      if (n < 0) n + b else n
    }

    // dirs are 0-7, starting at north and going clockwise
    // these are the deltas in order to move one step in given dir
    private val dirDelta = Map(0 -> (0, -1),
      1 -> (1, -1),
      2 -> (1, 0),
      3 -> (1, 1),
      4 -> (0, 1),
      5 -> (-1, 1),
      6 -> (-1, 0),
      7 -> (-1, -1))

    // returns the location one step in the given dir. Note the world is a torus
    def deltaLoc(loc: (Int, Int), dir: Int) = {
      val (dx, dy) = dirDelta(bound(8, dir))
      (bound(Dim, loc._1 + dx), bound(Dim, loc._2 + dy))
    }

    // given a vector of slice sizes, returns the index of a slice given a
    // random spin of a roulette wheel with compartments proportional to
    // slices.
    final def wrand(slices: Seq[Int]) = {
      val total = slices reduceLeft (_ + _)
      val r = randomInt(total)
      // Use recursion to match closure implementaion, (a while loop with vars would look simpler)
      @tailrec
      def loop(i: Int, sum: Int): Int =
        if (r < slices(i) + sum) i
        else loop(i + 1, slices(i) + sum) // TCO
      loop(0, 0)
    }
  }

  class AntBehaviour(initLoc: (Int, Int)) extends Actor {

    import Util._

    def receive = {
      case Ping => self ! initLoc
      case loc: (Int, Int) => {
        if (AntMillis > 0) Thread.sleep(AntMillis)
        self ! behave(loc)
      }
      case _ => println("received unknown message")
    }

    // turns the ant at the location by the given amount
    def turn(amt: Int)(loc: (Int, Int)) = {
      World(loc) changeAnt (_.turn(amt))
      loc
    }

    def moveToRandomCellWeightedByRank(loc: (Int, Int), rankingFunction: (CellRef) => Float): (Int, Int) = {
      import Util._
      val p = World(loc)
      val ant = p.ant
      val delta = (amt: Int) => World(deltaLoc(loc, ant.dir + amt))
      val ahead = delta(0)
      val aheadLeft = delta(-1)
      val aheadRight = delta(+1)
      val placesToGo = Seq(ahead, aheadLeft, aheadRight)
      val ranks = rankBy(rankingFunction, placesToGo) // rank the possible new places, by the rankingFunction (e.g. food is good, home is good). Gives a map of (place->placeGoodness) 
      val ranked = Seq(if (ahead.occupied) 0 else ranks(ahead), ranks(aheadLeft), ranks(aheadRight))
      val weightedRandomWayToGoZeroOneOrTwo = wrand(ranked)
      Seq(move _, turn(-1) _, turn(1) _)(weightedRandomWayToGoZeroOneOrTwo)(loc) // Matches clojure code
    }

    // the main function for the ant
    def behave(loc: (Int, Int)) : (Int, Int) = {
      //print(".")
      atomic {
        implicit txn =>
          //print(">")
          val current = World(loc)
          val ant = current.ant
          val ahead = World(deltaLoc(loc, ant.dir))
          if (ant.food) { // going home
            if (current.home) {
              dropFood(loc)
            } else if (ahead.home && !ahead.occupied) {
              move(loc) // nearly home!
            } else { // not home yet, follow the pheromones!
              moveToRandomCellWeightedByRank(loc, cell => cell.pher + (100 * (if (cell.home) 1 else 0)))
            }
          } else { // foraging
            if (current.food > 0 && !current.home) {
              takeFood(loc) // food right here,  take the food, turn round
            } else if (ahead.food > 0 && !ahead.home && !ahead.occupied) {
              move(loc) // food dead ahead
            } else { // hunt for food 
              moveToRandomCellWeightedByRank(loc, cell => cell.food + cell.pher)
            }
          }
//      } orAtomic { implicit txn =>
//        println ("<")
//        loc
      }
    }

    //  "moves the ant in the direction it is heading. Must be called in a
    //  transaction that has verified the way is clear"
    def move(loc: (Int, Int)) = {
      val from = World(loc)
      val ant = from.ant
      val newLocation = deltaLoc(loc, ant.dir)
      val to = World(newLocation)
      from.removeAnt
      to.addAnt(ant)
      if (!from.home) from pher (1.0f+)
      newLocation
    }

    //  "Takes one food from current location. Must be called in a
    //  transaction that has verified there is food available"
    def takeFood(loc: (Int, Int)) = {
      World(loc).changeFood(_ - 1)
      World(loc).changeAnt(_.copy(food = true).turnAround)
      loc
    }

    //  "Drops food at current location. Must be called in a
    //  transaction that has verified the ant has food"
    def dropFood(loc: (Int, Int)) = {
      World(loc).changeFood(_ + 1)
      World(loc).changeAnt(_.copy(food = false).turnAround)
      loc
    }

  }

  trait WorldActor extends Actor {
  }

  class Evaporator extends WorldActor {
    override def receive = {
      case Ping =>
        for (x <- 0 until Dim; y <- 0 until Dim) {
          World(x, y).pherIfNonZero(EvapRate *)
        }
    }
  }


  class Animator extends WorldActor {

    import java.awt.event.{WindowAdapter, WindowEvent}
    import java.awt.image.BufferedImage
    import java.awt.{Color, Dimension, Graphics}
    import javax.swing.{ImageIcon, JFrame, JPanel}

    // pixels per world cell
    private val scale = 5
    private val black = new Color(0, 0, 0, 255)
    private val red = new Color(255, 0, 0, 255)
    private val antLines = Map(0 -> (2, 0, 2, 4),
      1 -> (4, 0, 0, 4),
      2 -> (4, 2, 0, 2),
      3 -> (4, 4, 0, 0),
      4 -> (2, 4, 2, 0),
      5 -> (0, 4, 4, 0),
      6 -> (0, 2, 4, 2),
      7 -> (0, 0, 4, 4))

    def fillCell(g: Graphics, x: Int, y: Int, c: Color) = {
      g.setColor(c)
      g.fillRect(x * scale, y * scale, scale, scale)
    }

    def renderAnt(ant: Ant, g: Graphics, x: Int, y: Int) = {
      val (hx, hy, tx, ty) = antLines(ant.dir)
      g.setColor(if (ant.food) red else black)
      g.drawLine(hx + x * scale, hy + y * scale, tx + x * scale, ty + y * scale)
    }

    def renderPlace(g: Graphics, p: Cell, x: Int, y: Int) = {
      if (p.pher > 0)
        fillCell(g, x, y, new Color(0, 255, 0,
          min(255, 255 * (p.pher / PherScale)).toInt));
      if (p.food > 0)
        fillCell(g, x, y, new Color(255, 0, 0,
          min(255, 255 * (p.food / FoodScale)).toInt));

      if (p.ant.isDefined)
        renderAnt(p.ant.get, g, x, y)
    }

    def render(g: Graphics) = {
      val v = World.snapshot
      if (v.size == 0) {
        println("No world to render")
      }
      val img = new BufferedImage(scale * Dim, scale * Dim, BufferedImage.TYPE_INT_ARGB)
      val bg = img.getGraphics
      bg.setColor(Color.WHITE)
      bg.fillRect(0, 0, img.getWidth, img.getHeight)
      for (x <- 0 until Dim; y <- 0 until Dim) {
        // TODO tim new code : can this be more elegant? .single.get.get
        renderPlace(bg, v(x * Dim + y).single.get.get, x, y)
      }
      bg.setColor(Color.BLUE)
      bg.drawRect(scale * HomeOff, scale * HomeOff, scale * HomeSize, scale * HomeSize)
      g.drawImage(img, 0, 0, null)
      bg.dispose
    }

    val panel = new JPanel {
      override def paint(g: Graphics) { render(g) }
      setPreferredSize(new Dimension(scale * Dim, scale * Dim))
    }

    val frame = {
      val f = new JFrame
      f add (panel)
      f.pack
      f.setVisible(true)
      f.setTitle("Ants")
      val iconName = "Ant-icon.png"
      val urlViaClassloader = ClassLoader.getSystemResource(iconName)
      // Get the application icon. (ClassLoader.getSystemResource() only works for the runnable jar Assembly, but not for "sbt run"
      val iconUrl = Thread.currentThread().getContextClassLoader().getResource(iconName) // For "sbt run"
      if (iconUrl != null) f.setIconImage(new ImageIcon(iconUrl).getImage())
      f.addWindowListener(new WindowAdapter() {
        override def windowClosing(winEvt: WindowEvent) {
          System.exit(0)
        }
      });
    }

    override def receive = {
      case Ping =>
        panel.repaint()
    }
  }

}

object AntRunner {

  def getParams = {
    import javax.swing.JOptionPane
    val c = Seq("Yes", "No", "No idea!").toArray[Object]
    def getParam(question: String, options: Seq[String], defaultValue: Object): Int = {
      val dialogResult: AnyRef = JOptionPane.showInputDialog(
        null, question, "Ants", JOptionPane.PLAIN_MESSAGE, null, options.toArray[Object], defaultValue)
      if (dialogResult == null) throw new RuntimeException("Bye")
      dialogResult.asInstanceOf[String].toInt
    }
    val n = getParam("How many ants?", (1 to 100) map (v => (v * v).toString), "49");
    val antSleepMilliseconds = getParam("Ant millisecond sleep time?", ((0 to 100) map (v => v.toString)), "40");
    val consistent = JOptionPane.showOptionDialog(
      null, "Draw screen with consistent world snapshot as per clojure version?", "Ants", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, c, c(2))

    import scala.math.sqrt
    (sqrt(n).toInt, antSleepMilliseconds, consistent == 0)
  }

  def main(args: Array[String]) {

    val (nantsSqrt, antSleepMilliseconds, consistent) = getParams
    val antSimulation = new Ants(nantsSqrt, antSleepMilliseconds, consistent)
    antSimulation.World.start

  }
}
