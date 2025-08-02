package auf.memdex

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.server.handler.HandlerList


class Main {
    static void main(String[] args) {
        def server = new Server(8080)

        // Static files context for /static/*
        def staticContext = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
        staticContext.setContextPath("/static")
        staticContext.addServlet(DefaultServlet, "/*")
        // Use the build/resources/main/static directory for static files (classpath output)
        def staticDir = new File("build/resources/main/static")
        staticContext.setResourceBase(staticDir.absolutePath)
        staticContext.setInitParameter("dirAllowed", "false")

        // Dynamic servlet context for everything else
        def context = new ServletContextHandler(ServletContextHandler.SESSIONS)
        context.setContextPath("/")
        context.addServlet(new ServletHolder(new MainServlet()), "/*")

        // Combine handlers - try static files first, then servlet
        def handlers = new HandlerList()
        handlers.setHandlers([staticContext, context] as org.eclipse.jetty.server.Handler[])

        server.setHandler(handlers)
        server.start()
        server.join()
    }
}

class MainServlet extends HttpServlet {
    private final ModuleParser parser = new ModuleParser()
    private final Map<String, Map> modules = [:]
    private final DatabaseService dbService = new DatabaseService()
    private final ExecutionEngine engine = new ExecutionEngine(dbService, modules)

    @Override
    void init() {
        // On startup, find all .txt files in the 'modules' directory and parse them.
        def moduleDir = new File('modules')
        if (!moduleDir.exists()) moduleDir.mkdir()

        moduleDir.eachFileMatch(~/.*\.txt/) { file ->
            println "Loading module: ${file.name}"
            def module = parser.parseModule(file.text)
            modules[module.type] = module
        }

    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        resp.setContentType("text/html")
        def pathInfo = req.getPathInfo()
        def parts = pathInfo ? pathInfo.split('/').findAll { it } : []

        // Root route: list modules with #home
        if (!parts || parts.size() == 0) {
            def welcomeHtml = new StringBuilder()
            welcomeHtml.append("<h2>Available Modules</h2>")

            if (modules.isEmpty()) {
                welcomeHtml.append('<div class="incontact-row">')
                welcomeHtml.append('<div class="incontact-cell"><em>No modules available</em></div>')
                welcomeHtml.append('</div>')
            } else {
                modules.each { type, module ->
                    if (module.functions['#home']) {
                        welcomeHtml.append('<div class="incontact-row">')
                        welcomeHtml.append('<div class="incontact-cell"><strong>')
                        welcomeHtml.append(module.name ?: type)
                        welcomeHtml.append('</strong></div>')
                        welcomeHtml.append('<div class="incontact-cell">')
                        welcomeHtml.append('<a href="/module/')
                        welcomeHtml.append(type)
                        welcomeHtml.append('/home" class="button">Open</a>')
                        welcomeHtml.append('</div>')
                        welcomeHtml.append('</div>')
                    }
                }
            }

            resp.writer.println(createPage("Memdex", welcomeHtml.toString()))
            return
        }

        // Handle construction button requests: /construct/module_type/function
        if (parts.size() >= 3 && parts[0] == 'construct') {
            def moduleType = parts[1]
            def functionName = "#${parts[2]}"
            def module = modules[moduleType]

            if (!module) {
                resp.writer.println("<h3>Error: Module '${moduleType}' not found.</h3>")
                return
            }

            // Create new record - this returns a UUID string
            def newRecordUuid = dbService.createRecord(moduleType)
            // Get the actual record object
            def newRecord = dbService.getRecord(newRecordUuid)

            // Execute #construct function if it exists
            if (module.functions['#construct']) {
                engine.execute(module, '#construct', [record: newRecord])
            }

            // Handle parent relationship if specified
            def parentUuid = req.getParameter("parent_uuid")
            def storageVar = req.getParameter("storage_var")
            if (parentUuid && storageVar) {
                def parentRecord = dbService.getRecord(parentUuid)
                if (parentRecord) {
                    if (!parentRecord.data[storageVar]) {
                        parentRecord.data[storageVar] = []
                    }
                    if (parentRecord.data[storageVar] instanceof String) {
                        parentRecord.data[storageVar] = [parentRecord.data[storageVar]]
                    }
                    parentRecord.data[storageVar] << newRecord.uuid
                    dbService.saveRecord(parentRecord)
                }
            }

            // Always render the requested function for the new record (e.g., #setup)
            def context = [record: newRecord]
            def html = engine.execute(module, functionName, context)
            resp.writer.println(createPage(module.name, html))
            return
        }

        // Handle regular module requests: /module/module_type/function
        if (parts.size() < 3 || parts[0] != 'module') {
            // If the request is for a static file, do not render HTML
            if (req.getRequestURI().startsWith("/static/")) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                return
            }

            // Show debug information for /module route
            if (parts.size() == 1 && parts[0] == 'module') {
                def debugHtml = new StringBuilder()
                debugHtml.append("<h1>Module Debug Information</h1>")
                debugHtml.append("<h2>Loaded Modules (${modules.size()})</h2>")

                if (modules.isEmpty()) {
                    debugHtml.append("<p><em>No modules loaded</em></p>")
                } else {
                    modules.each { type, module ->
                        debugHtml.append("<div style='border: 1px solid #ccc; margin: 10px 0; padding: 10px;'>")
                        debugHtml.append("<h3>Module: ${type}</h3>")
                        debugHtml.append("<p><strong>Name:</strong> ${module.name ?: 'Not set'}</p>")
                        debugHtml.append("<p><strong>Type:</strong> ${module.type}</p>")

                        // Show variables
                        debugHtml.append("<h4>Variables (${module.vars?.size() ?: 0})</h4>")
                        if (module.vars && !module.vars.isEmpty()) {
                            debugHtml.append("<ul>")
                            module.vars.each { varName, varValue ->
                                debugHtml.append("<li><strong>${varName}:</strong> ")
                                if (varValue instanceof List) {
                                    debugHtml.append("[${varValue.join(', ')}]")
                                } else {
                                    debugHtml.append("${varValue}")
                                }
                                debugHtml.append("</li>")
                            }
                            debugHtml.append("</ul>")
                        } else {
                            debugHtml.append("<p><em>No variables defined</em></p>")
                        }

                        // Show functions
                        debugHtml.append("<h4>Functions (${module.functions?.size() ?: 0})</h4>")
                        if (module.functions && !module.functions.isEmpty()) {
                            debugHtml.append("<ul>")
                            module.functions.each { funcName, func ->
                                debugHtml.append("<li><strong>${funcName}</strong> (${func.script?.size() ?: 0} lines)")
                                if (funcName == '#home') {
                                    debugHtml.append(" <a href='/module/${type}/home'>[Test]</a>")
                                }
                                debugHtml.append("</li>")
                            }
                            debugHtml.append("</ul>")
                        } else {
                            debugHtml.append("<p><em>No functions defined</em></p>")
                        }

                        debugHtml.append("</div>")
                    }
                }

                // Show database records
                debugHtml.append("<h2>Database Records</h2>")
                def dbDir = new File('database')
                if (dbDir.exists()) {
                    def recordCount = 0
                    def moduleTypeCount = [:]

                    dbDir.eachFileMatch(~/.*\.json/) { file ->
                        try {
                            def record = new groovy.json.JsonSlurper().parse(file)
                            recordCount++
                            def moduleType = record.module_type ?: 'unknown'
                            moduleTypeCount[moduleType] = (moduleTypeCount[moduleType] ?: 0) + 1
                        } catch (Exception e) {
                            // Skip malformed files
                        }
                    }

                    debugHtml.append("<p><strong>Total Records:</strong> ${recordCount}</p>")
                    debugHtml.append("<h4>Records by Module Type:</h4>")
                    debugHtml.append("<ul>")
                    moduleTypeCount.each { type, count ->
                        debugHtml.append("<li><strong>${type}:</strong> ${count} records</li>")
                    }
                    debugHtml.append("</ul>")
                } else {
                    debugHtml.append("<p><em>Database directory not found</em></p>")
                }

                resp.writer.println(createPage("Module Debug", debugHtml.toString()))
                return
            }

            resp.writer.println("<h1>Welcome to Memdex</h1>")
            return
        }

        def moduleType = parts[1]
        def functionName = "#${parts[2]}"
        def module = modules[moduleType]
        def uuid = req.getParameter("uuid")

        def context = [:]
        if (uuid) {
            context.record = dbService.getRecord(uuid)
        }

        // Add querystring parameters to context for & references
        def querystring = [:]
        req.getParameterNames().each { paramName ->
            querystring[paramName] = req.getParameter(paramName)
        }
        context.querystring = querystring

        def html = engine.execute(module, functionName, context)
        // Create page with debug information
        def record = context.record ?: [data:[:]]
        resp.writer.println(createPageWithDebug(module.name, html, module, record.data, context))

        // If no uuid is provided for a module with a #home, redirect to the first or create one
        if (parts.size() == 2 && parts[0] == 'module') {
            def mt = parts[1]
            def mod = modules[mt]
            if (mod && mod.functions['#home']) {
                // Try to find an existing record for this module type
                def dbDir = new File('database')
                def foundUuid = null
                dbDir.eachFileMatch(~/.*\.json/) { file ->
                    def rec = new groovy.json.JsonSlurper().parse(file)
                    if (rec.module_type == mt) {
                        foundUuid = rec.uuid
                        return false // break
                    }
                }
                // If not found, create one
                if (!foundUuid) {
                    foundUuid = dbService.createRecord(mt)
                }
                // Redirect to the #home with the uuid
                resp.sendRedirect("/module/${mt}/home?uuid=${foundUuid}")
                return
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        // Support both JSON and form-encoded requests
        String uuid = null
        String varName = null
        String value = null
        if (req.getContentType() != null && req.getContentType().contains("application/json")) {
            def json = new groovy.json.JsonSlurper().parse(req.inputStream)
            uuid = json.uuid
            varName = json.varName
            value = json.value
        } else {
            uuid = req.getParameter("uuid")
            varName = req.getParameter("varName")
            value = req.getParameter("value")
        }

        if (uuid && varName) {
            def record = dbService.getRecord(uuid)
            if (record) {
                record.data[varName] = value
                dbService.saveRecord(record)
                resp.setStatus(HttpServletResponse.SC_OK)
                resp.writer.println('{"success":true}')
            } else {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Record not found")
            }
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing parameters")
        }
    }

    private String createPage(String title, String content) {
        return createPageWithDebug(title, content, null, null, null)
    }

    private String createPageWithDebug(String title, String content, Map module, Map recordVars, Map context) {
        def debugScript = ""
        if (module && recordVars != null) {
            def debugData = [
                module_type: module.type,
                module_name: module.name,
                module_vars: module.vars ?: [:],
                record_vars: recordVars,
                record_uuid: context?.record?.uuid,
                querystring: context?.querystring ?: [:]
            ]

            def debugJson = groovy.json.JsonOutput.toJson(debugData)
            debugScript = """
            <script>
                console.group('🔍 Memdex Debug Info');
                console.log('Module Type:', '${module.type}');
                console.log('Module Name:', '${module.name ?: 'Not set'}');
                console.log('Module Variables:', ${groovy.json.JsonOutput.toJson(module.vars ?: [:])});
                console.log('Record Variables:', ${groovy.json.JsonOutput.toJson(recordVars)});
                console.log('Record UUID:', '${context?.record?.uuid ?: 'None'}');
                console.log('Querystring:', ${groovy.json.JsonOutput.toJson(context?.querystring ?: [:])});
                console.groupEnd();
            </script>
            """
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <title>${title} - InContact</title>
            <link rel="stylesheet" href="/static/style.css">
        </head>
        <body>
            <div class="home-icon-container">
                <a href="/" class="home-icon">
                    <img src="/static/home-icon.svg" alt="Home" width="24" height="24">
                </a>
            </div>
            <div class="container">
                <h1>${title}</h1>
                <hr>
                ${content}
            </div>
            <script src="/static/app.js"></script>
            ${debugScript}
        </body>
        </html>
        """
    }
}