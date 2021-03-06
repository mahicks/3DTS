/*
 * The MIT License
 *
 * Copyright (c) 2015 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland,
 * Group Fellay
 * Modified work, Copyright (c) 2016 Istvan Bartha

 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tasks.fileservice

import scala.collection.JavaConversions._
import java.io.File
import tasks._
import tasks.deploy._
import tasks.util._
import scala.concurrent.ExecutionContext.Implicits.global

object SharedFileTestApp extends App {

  withTaskSystem { implicit ts =>
    0 to 10 foreach { i =>
      val tmp = TempFile.createTempFile("random.txt")
      writeToFile(tmp, scala.util.Random.nextInt.toString)
      println("A")
      SharedFile(tmp, name = "random" + i.toString)
      println("B")
    }
    0 to 10 foreach { i =>
      println("C")

      val tmp = TempFile.createTempFile("fix.txt")
      writeToFile(tmp, i.toString)
      SharedFile(tmp, name = "fix" + i.toString)
      println("D")

    }
    tasks.util.config.load.getString("importfiles").split(",").map { f =>
      SharedFile(new File(f), name = new File(f).getName)
    }

  }
  // t
}
