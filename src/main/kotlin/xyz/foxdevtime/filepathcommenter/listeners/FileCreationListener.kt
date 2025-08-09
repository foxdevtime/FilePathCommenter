package xyz.foxdevtime.filepathcommenter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent


class FileCreationListener : AsyncFileListener {

    private data class PendingChange(
        val parent: VirtualFile,
        val childName: String,
        val isDirectory: Boolean
    )

    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val pendingChanges = mutableListOf<PendingChange>()

        for (event in events) {
            if (event !is VFileCreateEvent) {
                continue
            }
            pendingChanges.add(PendingChange(event.parent, event.childName, event.isDirectory))
        }

        if (pendingChanges.isEmpty()) {
            return null
        }

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                ApplicationManager.getApplication().invokeLater {
                    for (pendingChange in pendingChanges) {
                        if (pendingChange.isDirectory) {
                            continue
                        }

                        val file = pendingChange.parent.findChild(pendingChange.childName) ?: continue
                        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: continue

                        val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(file)
                        val relativePath = if (contentRoot != null) {
                            VfsUtilCore.getRelativePath(file, contentRoot) ?: file.path
                        } else {
                            file.path
                        }

                        val comment = getCommentForFile(file, relativePath) ?: continue

                        val document = FileDocumentManager.getInstance().getDocument(file)
                        if (document != null) {
                            WriteCommandAction.runWriteCommandAction(project) {
                                document.insertString(0, "$comment\n\n")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getCommentForFile(file: VirtualFile, path: String): String? {
        return when (file.extension?.lowercase()) {
            "py", "txt", "php", "yaml", "yml" -> "# $path"
            "java", "js", "ts", "c", "cpp", "h", "cs", "go", "kt", "rs", "swift" -> "// $path"
            "xml", "html" -> "<!-- $path -->"
            "css" -> "/* $path */"
            "sql" -> "-- $path"
            "json" -> "???" // can I create comment in main body json? {"_comment": "path/to/file"}
            else -> {
                if (file.fileType is PlainTextFileType) "# $path" else null
            }
        }
    }
}