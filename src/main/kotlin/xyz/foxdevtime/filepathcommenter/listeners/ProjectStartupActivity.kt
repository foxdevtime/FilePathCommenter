package xyz.foxdevtime.filepathcommenter.listeners

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

class ProjectCreationProcessor : ProjectActivity {

    override suspend fun execute(project: Project) {
        println("[ProjectCreationProcessor] DEBUG: 'execute' метод запущен для проекта: ${project.name}")

        val propertyKey = "xyz.foxdevtime.filepathcommenter.processed.${project.locationHash}"
        val properties = PropertiesComponent.getInstance(project)

        if (properties.isTrueValue(propertyKey)) {
            println("[ProjectCreationProcessor] DEBUG: Проект уже обработан. Выходим.")
            return
        }

        println("[ProjectCreationProcessor] DEBUG: Проект новый. Планируем выполнение после завершения индексации (runWhenSmart).")

        // Это ключевое исправление: мы просим выполнить наш код, когда проект станет "умным"
        // (т.е. когда индексирование завершится).
        DumbService.getInstance(project).runWhenSmart {
            println("[ProjectCreationProcessor] DEBUG: Проект вышел из 'Dumb Mode'. Запускаем основную логику.")
            addCommentsToProjectFiles(project, properties, propertyKey)
        }
    }

    private fun addCommentsToProjectFiles(project: Project, properties: PropertiesComponent, propertyKey: String) {
        println("[ProjectCreationProcessor] DEBUG: Начинаем поиск файлов для обработки.")
        val projectScope = GlobalSearchScope.projectScope(project)
        val filesToProcess = mutableListOf<VirtualFile>()

        // Собираем все файлы, которые нам интересны
        val fileTypes = listOf("py", "java", "js", "ts", "cpp", "h", "cs", "go", "kt", "rs", "swift", "xml", "html", "sql", "txt", "css", "php")
        fileTypes.forEach { ext ->
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
            val foundFiles = FileTypeIndex.getFiles(fileType, projectScope)
            println("[ProjectCreationProcessor] DEBUG:   -> Ищем расширение '$ext'. Найдено файлов: ${foundFiles.size}")
            foundFiles.forEach {
                filesToProcess.add(it)
            }
        }

        println("[ProjectCreationProcessor] DEBUG: Всего найдено для обработки: ${filesToProcess.size} файлов.")
        if (filesToProcess.isEmpty()) {
            println("[ProjectCreationProcessor] DEBUG: Файлов для обработки нет. Завершаем.")
            return
        }

        println("[ProjectCreationProcessor] DEBUG: Запускаем WriteCommandAction для изменения файлов.")
        // Так как runWhenSmart выполняется в EDT, используем обычный WriteCommandAction
        WriteCommandAction.runWriteCommandAction(project) {
            var modifiedCount = 0
            for (file in filesToProcess) {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                if (document.textLength > 0 && (document.text.startsWith("//")
                            || document.text.startsWith("#") || document.text.startsWith("<!--")
                            || document.text.startsWith("/*") || document.text.startsWith("--"))) {
                    continue
                }

                val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(file)
                val relativePath = if (contentRoot != null) {
                    VfsUtilCore.getRelativePath(file, contentRoot) ?: file.path
                } else {
                    file.path
                }

                val comment = getCommentForFile(file, relativePath) ?: continue
                document.insertString(0, "$comment\n\n")
                modifiedCount++
                println("[ProjectCreationProcessor] DEBUG:     -> УСПЕХ: Добавлен комментарий в файл ${file.name}")
            }
            println("[ProjectCreationProcessor] DEBUG: Готово. Изменено ${modifiedCount} файлов.")

            // Устанавливаем флаг только после успешной обработки
            properties.setValue(propertyKey, true)
            println("[ProjectCreationProcessor] DEBUG: Установлен флаг 'обработано' для проекта.")
        }
    }

    private fun getCommentForFile(file: VirtualFile, path: String): String? {
        return when (file.extension?.lowercase()) {
            "py", "txt", "php" -> "# $path"
            "java", "js", "ts", "cpp", "h", "cs", "go", "kt", "rs", "swift" -> "// $path"
            "xml", "html" -> "<!-- $path -->"
            "css" -> "/* $path */"
            "sql" -> "-- $path"
            else -> {
                if (file.fileType is PlainTextFileType) "# $path" else null
            }
        }
    }
}