package com.spekterm.android.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSpecTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun aChangeListKeepsEveryWellFormedChange() {
        val changes = OpenSpec.changes(json("""
            {"active":[
              {"slug":"add-thing","status":"active","description":"add thing","createdDate":"2026-10-17","archivedDate":null,
               "timestamp":null,"hasProposal":true,"hasDesign":true,"hasSpecs":true,"hasTasks":true,
               "taskStats":{"total":12,"completed":5},"worktree":{"branch":null,"isMain":false}},
              {"status":"active"},
              7,
              {"slug":"bare"}
            ],
            "archived":[{"slug":"2026-10-16-close-offer","status":"archived","description":"close offer","createdDate":"2026-10-15",
               "archivedDate":"2026-10-16","timestamp":"2026-10-16T10:00:00+08:00","taskStats":null}]}
        """))!!
        assertEquals(listOf("add-thing", "bare"), changes.active.map { it.slug })
        val first = changes.active[0]
        assertEquals(TaskStats(12, 5), first.taskStats)
        assertEquals(Worktree(null, false), first.worktree)
        assertEquals("2026-10-17", first.createdDate)
        val bare = changes.active[1]
        assertNull(bare.taskStats)
        assertNull(bare.worktree)
        val archived = changes.archived.single()
        assertTrue(archived.archived)
        assertNull(archived.taskStats)
        assertEquals("2026-10-16", archived.archivedDate)
        assertNull(OpenSpec.changes(json("[]")))
    }

    @Test
    fun anArtifactOfEachKind() {
        val detail = OpenSpec.changeDetail(json("""
            {"slug":"add-thing","status":"active","createdDate":null,"archivedDate":null,"artifacts":[
              {"id":"proposal","title":"Proposal","kind":"markdown","content":"# Why\n"},
              {"id":".openspec.yaml","title":".openspec.yaml","kind":"data","content":"schema: spec-driven\n"},
              {"id":"tasks","title":"Tasks","kind":"tasks","tasks":{"total":2,"completed":1,"sections":[
                {"title":"1. Protocol","tasks":[{"text":"1.1 Done","completed":true},{"text":"1.2 Open\nsecond line","completed":false}]}]}},
              {"id":"specs","title":"Specs","kind":"specs","specs":[{"topic":"a","content":"## ADDED"},{"topic":"b","content":"## MODIFIED"}]}
            ]}
        """))!!
        val (proposal, data, tasks, specs) = detail.artifacts
        assertEquals(OsArtifact.Markdown("proposal", "Proposal", Piece.Present("# Why\n")), proposal)
        assertEquals(OsArtifact.Data(".openspec.yaml", ".openspec.yaml", Piece.Present("schema: spec-driven\n")), data)
        val list = ((tasks as OsArtifact.Tasks).tasks as Piece.Present).value
        assertEquals(2, list.total)
        assertEquals(1, list.completed)
        assertEquals(listOf(TaskList.Task("1.1 Done", true), TaskList.Task("1.2 Open\nsecond line", false)), list.sections.single().tasks)
        assertEquals("1. Protocol", list.sections.single().title)
        assertEquals(listOf(Delta("a", Piece.Present("## ADDED")), Delta("b", Piece.Present("## MODIFIED"))), (specs as OsArtifact.Specs).deltas)
    }

    @Test
    fun anUnknownOrUnreadableArtifactIsShownByItsTitleAndOneWithoutAnIdIsSkipped() {
        val detail = OpenSpec.changeDetail(json("""
            {"slug":"x","status":"active","artifacts":[
              {"id":"diagram","title":"Diagram","kind":"svg","content":"<svg/>"},
              {"id":"design","title":"Design","kind":"markdown","content":42},
              {"title":"No id","kind":"markdown","content":"text"},
              {"id":"notes","kind":"markdown","content":"n"}
            ]}
        """))!!
        assertEquals(
            listOf(OsArtifact.Other("diagram", "Diagram"), OsArtifact.Other("design", "Design"), OsArtifact.Markdown("notes", "notes", Piece.Present("n"))),
            detail.artifacts,
        )
    }

    @Test
    fun omittedPiecesStandInForTheirContent() {
        val detail = OpenSpec.changeDetail(json("""
            {"slug":"x","status":"active","artifacts":[
              {"id":"design","title":"Design","kind":"markdown","omitted":{"bytes":600000}},
              {"id":"tasks","title":"Tasks","kind":"tasks","omitted":{"bytes":52000}},
              {"id":"specs","title":"Specs","kind":"specs","specs":[{"topic":"a","omitted":{"bytes":87000}},{"topic":"b","content":"ok"}]}
            ]}
        """))!!
        assertEquals(Piece.Omitted(600000), (detail.artifacts[0] as OsArtifact.Markdown).content)
        assertEquals(Piece.Omitted(52000), (detail.artifacts[1] as OsArtifact.Tasks).tasks)
        assertEquals(listOf(Delta("a", Piece.Omitted(87000)), Delta("b", Piece.Present("ok"))), (detail.artifacts[2] as OsArtifact.Specs).deltas)
        val spec = OpenSpec.spec(json("""{"topic":"t","omitted":{"bytes":2000000},"history":[]}"""))!!
        assertEquals(Piece.Omitted(2000000), spec.content)
    }

    @Test
    fun anOlderDesktopsPassThroughAnswersReadTheSame() {
        val changes = OpenSpec.changes(json("""
            {"defaultSchema":"spec-driven","active":[{"slug":"add-thing","status":"active","description":"add thing","date":null,
              "createdDate":"2026-10-17","archivedDate":null,"timestamp":null,"artifactCount":4,"schema":"spec-driven",
              "relPath":"openspec/changes/add-thing","taskStats":{"total":2,"completed":1},
              "worktree":{"key":"/abs","branch":"feat","isMain":false,"vcs":"git","isFolderRoot":false}}],"archived":[]}
        """))!!
        assertEquals(OsChange("add-thing", "active", "add thing", "2026-10-17", null, null, TaskStats(2, 1), Worktree("feat", false)), changes.active.single())
        val detail = OpenSpec.changeDetail(json("""
            {"slug":"add-thing","status":"active","schema":"spec-driven","schemaOrder":["proposal"],"missingArtifacts":[],"artifacts":[
              {"id":"tasks","title":"Tasks","kind":"tasks","relPath":"openspec/changes/add-thing/tasks.md","content":"- [x] 1.1 Done\n",
               "tasks":{"total":1,"completed":1,"sections":[{"title":"","tasks":[{"text":"1.1 Done","completed":true}]}]}}]}
        """))!!
        val tasks = ((detail.artifacts.single() as OsArtifact.Tasks).tasks as Piece.Present).value
        assertEquals(listOf(TaskList.Task("1.1 Done", true)), tasks.sections.single().tasks)
        val spec = OpenSpec.spec(json("""
            {"topic":"thing","relPath":"openspec/specs/thing/spec.md","content":"# thing","relatedChanges":["add-thing"],
             "history":[{"slug":"add-thing","status":"active","description":"add thing","date":null,"timestamp":null}]}
        """))!!
        assertEquals(OsSpec("thing", Piece.Present("# thing"), listOf(HistoryEntry("add-thing", "active", "add thing", null)), null), spec)
        val specs = OpenSpec.specs(json("""[{"topic":"thing","relPath":"openspec/specs/thing/spec.md","relatedChangeCount":1},{"relatedChangeCount":2}]"""))!!
        assertEquals(listOf(OsSpecSummary("thing", 1)), specs)
    }

    @Test
    fun aSpecWithHistoryAndOrigin() {
        val spec = OpenSpec.spec(json("""
            {"topic":"session-list","content":"# session-list","origin":{"branch":"master","isMain":true},"history":[
              {"slug":"2026-10-16-close-offer","status":"archived","description":"close offer","date":"2026-10-16","timestamp":"t"},
              {"status":"archived"}
            ]}
        """))!!
        assertEquals(Worktree("master", true), spec.origin)
        assertEquals(listOf(HistoryEntry("2026-10-16-close-offer", "archived", "close offer", "2026-10-16")), spec.history)
        assertTrue(spec.history.single().archived)
        assertNull(OpenSpec.spec(json("""{"content":"x"}""")))
    }

    @Test
    fun requestPayloads() {
        assertEquals("""{"folderId":"f"}""", Frames.openspecChanges("f").toString())
        assertEquals("""{"folderId":"f","slug":"s"}""", Frames.openspecChange("f", "s").toString())
        assertEquals("""{"folderId":"f"}""", Frames.openspecSpecs("f").toString())
        assertEquals("""{"folderId":"f","topic":"t"}""", Frames.openspecSpec("f", "t").toString())
        assertEquals("""{"folderId":"f"}""", Frames.openspecWatch("f").toString())
        assertEquals("""{"folderId":null}""", Frames.openspecWatch(null).toString())
    }
}
