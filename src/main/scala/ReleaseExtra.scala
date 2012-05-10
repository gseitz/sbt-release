package sbtrelease

import java.io.File
import sbt._
import Keys._
import sbt.Package.ManifestAttributes
import sbt.Aggregation.KeyValue
import annotation.tailrec

object ReleaseStateTransformations {
  import ReleasePlugin.ReleaseKeys._
  import Utilities._

  lazy val initialGitChecks: ReleasePart = { st =>
    if (!new File(".git").exists) {
      sys.error("Aborting release. Working directory is not a git repository.")
    }
    val status = (Git.status !!).trim
    if (!status.isEmpty) {
      sys.error("Aborting release. Working directory is dirty.")
    }
    st.log.info("Starting release process off git commit: " + Git.currentHash)
    st
  }

  lazy val checkSnapshotDependencies: ReleasePart = { st =>
    val thisRef = st.extract.get(thisProjectRef)
    val (newSt, result) = runTaskAggregated(snapshotDependencies in thisRef, st)
    val snapshotDeps = result match {
      case Value(value) => value.flatMap(_.value)
      case Inc(cause) => sys.error("Error checking for snapshot dependencies: " + cause)
    }
    val useDefs = newSt.get(useDefaults).getOrElse(false)
    if (!snapshotDeps.isEmpty) {
      if (useDefs) {
        sys.error("Aborting release due to snapshot dependencies.")
      } else {
        st.log.warn("Snapshot dependencies detected:\n" + snapshotDeps.mkString("\n"))
        SimpleReader.readLine("Do you want to continue (y/n)? [n] ") match {
          case Some("y") | Some("Y") =>
          case _ => sys.error("Aborting release due to snapshot dependencies.")
        }
      }
    }
    newSt
  }


  lazy val inquireVersions: ReleasePart = { st =>
    val extracted = Project.extract(st)

    val useDefs = st.get(useDefaults).getOrElse(false)
    val currentV = extracted.get(version)

    val releaseFunc = extracted.get(releaseVersion)
    val suggestedReleaseV = releaseFunc(currentV)

    val releaseV = readVersion(suggestedReleaseV, "Release version [%s] : ", useDefs)

    val nextFunc = extracted.get(nextVersion)
    val suggestedNextV = nextFunc(releaseV)
    val nextV = readVersion(suggestedNextV, "Next version [%s] : ", useDefs)

    st.put(versions, (releaseV, nextV))
  }


  lazy val runTest: ReleasePart = {st =>
    if (!st.get(skipTests).getOrElse(false)) {
      val extracted = Project.extract(st)
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(test in Test in ref, st)
    } else st
  }

  lazy val setReleaseVersion: ReleasePart = setVersion(_._1)
  lazy val setNextVersion: ReleasePart = setVersion(_._2)
  private def setVersion(selectVersion: Versions => String): ReleasePart =  { st =>
    val vs = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
    val selected = selectVersion(vs)

    st.log.info("Setting version to '%s'." format selected)


    val versionString = "%sversion in ThisBuild := \"%s\"%s" format (lineSep, selected, lineSep)
    IO.write(new File("version.sbt"), versionString)

    reapply(Seq(
      version in ThisBuild := selected
    ), st)
  }

  lazy val commitReleaseVersion: ReleasePart = { st =>
    val newState = commitVersion("Releasing %s")(st)
    reapply(Seq[Setting[_]](
      packageOptions += ManifestAttributes(
        "Git-Release-Hash" -> Git.currentHash
      )
    ), newState)
  }
  lazy val commitNextVersion: ReleasePart = commitVersion("Bump to %s")
  private def commitVersion(msgPattern: String): ReleasePart = { st =>
    val v = st.extract.get(version in ThisBuild)

    Git.add("version.sbt") !! st.log
    val status = (Git.status !!) trim

    if (status.nonEmpty) {
      Git.commit(msgPattern format v) ! st.log
    } else {
      // nothing to commit. this happens if the version.sbt file hasn't changed.
    }
    st
  }

  lazy val tagRelease: ReleasePart = { st =>
    @tailrec
    def findTag(tag: String): Option[String] = {
      if (Git.existsTag(tag)) {
        SimpleReader.readLine("Tag [%s] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a] " format tag) match {
          case Some("" | "a" | "A") =>
            sys.error("Aborting release!")

          case Some("k" | "K") =>
            st.log.warn("The current tag [%s] does not point to the commit for this release!" format tag)
            None

          case Some("o" | "O") =>
            st.log.warn("Overwriting a tag can cause problems if others have already seen the tag (see `git help tag`)!")
            Some(tag)

          case Some(newTag) =>
            findTag(newTag)

          case None =>
            sys.error("No tag entered. Aborting release!")
        }
      } else {
        Some(tag)
      }
    }

    val tag = st.extract.get(tagName)
    val tagToUse = findTag(tag)
    tagToUse.foreach(Git.tag(_, force = true) !! st.log)


    tagToUse map (t =>
      reapply(Seq[Setting[_]](
        packageOptions += ManifestAttributes("Git-Release-Tag" -> t)
      ), st)
    ) getOrElse st
  }

  lazy val pushChanges: ReleasePart = { st =>
    if (Git.hasUpstream) {
      SimpleReader.readLine("Push changes to the remote repository (y/n)? [y] ") match {
        case Some("y") | Some("Y") =>
          Git.pushCurrentBranch !! st.log
          Git.pushTags !! st.log
        case _ => st.log.warn("Remember to push the changes yourself!")
      }
    } else {
      st.log.info("Changes were NOT pushed, because no upstream branch is configured for the local branch [%s]" format Git.currentBranch)
    }
    st
  }

  private def readVersion(ver: String, prompt: String, useDef: Boolean): String = {
    if (useDef) ver
    else SimpleReader.readLine(prompt format ver) match {
      case Some("") => ver
      case Some(input) => Version(input).map(_.string).getOrElse(versionFormatError)
      case None => sys.error("No version provided!")
    }
  }

  def reapply(settings: Seq[Setting[_]], state: State) = {
    val extracted = state.extract
    import extracted._

    val append = Load.transformSettings(Load.projectScope(currentRef), currentRef.build, rootProject, settings)

    // We don't even want to be able to save the settings that are applied to the session during the release cycle.
    // Just using an empty string works fine and in case the user calls `session save`, empty lines will be generated.
		val newSession = session.appendSettings( append map (a => (a, Nil)))
		BuiltinCommands.reapply(newSession, structure, state)
  }
}


object ExtraReleaseCommands {
  import ReleaseStateTransformations._

  private lazy val initialGitChecksCommandKey = "release-git-checks"
  lazy val initialGitChecksCommand = Command.command(initialGitChecksCommandKey)(initialGitChecks)

  private lazy val checkSnapshotDependenciesCommandKey = "release-check-snapshot-dependencies"
  lazy val checkSnapshotDependenciesCommand = Command.command(checkSnapshotDependenciesCommandKey)(checkSnapshotDependencies)

  private lazy val inquireVersionsCommandKey = "release-inquire-versions"
  lazy val inquireVersionsCommand = Command.command(inquireVersionsCommandKey)(inquireVersions)

  private lazy val setReleaseVersionCommandKey = "release-set-release-version"
  lazy val setReleaseVersionCommand = Command.command(setReleaseVersionCommandKey)(setReleaseVersion)

  private lazy val setNextVersionCommandKey = "release-set-next-version"
  lazy val setNextVersionCommand = Command.command(setNextVersionCommandKey)(setNextVersion)

  private lazy val commitReleaseVersionCommandKey = "release-commit-release-version"
  lazy val commitReleaseVersionCommand =  Command.command(commitReleaseVersionCommandKey)(commitReleaseVersion)

  private lazy val commitNextVersionCommandKey = "release-commit-next-version"
  lazy val commitNextVersionCommand = Command.command(commitNextVersionCommandKey)(commitNextVersion)

  private lazy val tagReleaseCommandKey = "release-tag-release"
  lazy val tagReleaseCommand = Command.command(tagReleaseCommandKey)(tagRelease)

  private lazy val pushChangesCommandKey = "release-push-changes"
  lazy val pushChangesCommand = Command.command(pushChangesCommandKey)(pushChanges)
}


object Utilities {
  val lineSep = sys.props.get("line.separator").getOrElse(sys.error("No line separator? Really?"))

  class StateW(st: State) {
    def extract = Project.extract(st)
  }
  implicit def stateW(st: State): StateW = new StateW(st)

  private[sbtrelease] def resolve[T](key: ScopedKey[T], extracted: Extracted): ScopedKey[T] =
		Project.mapScope(Scope.resolveScope(GlobalScope, extracted.currentRef.build, extracted.rootProject) )( key.scopedKey )

  def runTaskAggregated[T](taskKey: TaskKey[T], state: State) = {
    import EvaluateTask._
    val extra = Aggregation.Dummies(KNil, HNil)
    val extracted = state.extract
    val config = extractedConfig(extracted, extracted.structure)

    val rkey = resolve(taskKey.scopedKey, extracted)
    val keys = Aggregation.aggregate(rkey, ScopeMask(), extracted.structure.extra)
    val tasks = Act.keyValues(extracted.structure)(keys)
    val toRun = tasks map { case KeyValue(k,t) => t.map(v => KeyValue(k,v)) } join;
    val roots = tasks map { case KeyValue(k,_) => k }


    val (newS, result) = withStreams(extracted.structure, state){ str =>
			val transform = nodeView(state, str, roots, extra.tasks, extra.values)
			runTask(toRun, state,str, extracted.structure.index.triggers, config)(transform)
		}
    (newS, result)
  }
}

