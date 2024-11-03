package org.evomaster.core.ai

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.image.CreateImageRequest;
import com.github.javaparser.JavaParser
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.Node
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.io.BufferedReader
import java.time.Duration
import java.net.SocketTimeoutException
import org.json.JSONObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and
import java.security.MessageDigest

data class MethodCall(val caller: String, val callee: String, val fileName: String, val del: MethodDeclaration, val call: MethodCallExpr)

class VariableDeclarationVisitor(private val variableName: String, private val sb: StringBuilder, private val visitedVariables: HashSet<String>) : VoidVisitorAdapter<Void?>() {
    override fun visit(variableDeclarator: VariableDeclarator, arg: Void?) {
        super.visit(variableDeclarator, arg)
        if (variableDeclarator.nameAsString == variableName) {
            sb.append("       Variable declaration found: $variableDeclarator\n")
            visitedVariables.add(variableName)
            variableDeclarator.initializer.ifPresent { initializer ->
                initializer.accept(object : VoidVisitorAdapter<Void?>() {
                    override fun visit(nameExpr: NameExpr, arg: Void?) {
                        super.visit(nameExpr, arg)
                        if (!visitedVariables.contains(nameExpr.nameAsString)) {
                            // b.append("     - Found nested variable: ${nameExpr.nameAsString}\n")
                            nameExpr.findAncestor(CompilationUnit::class.java).ifPresent { cu ->
                                cu.accept(VariableDeclarationVisitor(nameExpr.nameAsString, sb, visitedVariables), null)
                            }
                        }
                    }
                }, null)
            }
        }
    }
}



class LLM(
    ) {
        
        companion object {
            private val service = OpenAiService( System.getenv("OPENAI_TOKEN"), Duration.ofSeconds(60))
            private var messages = mutableListOf<Map<String, String>>()
            private var javaParser = JavaParser()
            private val repoPathsArray = System.getenv("REPO_PATH").split(",").toTypedArray()
            private val callGraph = buildCallGraph(repoPathsArray)

            fun buildCallGraph(repoPathsArray: Array<String>): List<MethodCall> {
                val callGraph = ConcurrentHashMap.newKeySet<MethodCall>()
            
                // Set up the type solver
                val typeSolver = CombinedTypeSolver(
                    ReflectionTypeSolver()
                )
                for (path in repoPathsArray) {
                    typeSolver.add(JavaParserTypeSolver(File(path)))
                }            
                val parserConfiguration = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
                javaParser = JavaParser(parserConfiguration)
            
                // Parse all Java files in the source root
                for (sourceRoot in repoPathsArray) {
                    val sourceFiles = File(sourceRoot).walkTopDown().filter { it.extension == "java" }.toList()
                    sourceFiles.forEach { file ->
                        val cu: CompilationUnit? = javaParser.parse(file).result.orElse(null)
                        cu?.accept(object : VoidVisitorAdapter<Void>() {
                            override fun visit(md: MethodDeclaration, arg: Void?) {
                                super.visit(md, arg)
                                var methodName = md.nameAsString
                                try {
                                    methodName = md.resolve().qualifiedSignature
                                } catch (e: Exception) {
                                }
                                md.accept(object : VoidVisitorAdapter<Void>() {
                                    override fun visit(mce: MethodCallExpr, arg: Void?) {
                                        super.visit(mce, arg)
                                        try {
                                            val resolvedMethod = mce.resolve()
                                            val calleeName = resolvedMethod.qualifiedSignature
                                            callGraph.add(MethodCall(caller = methodName, callee = calleeName, fileName = file.absolutePath, del = md, call = mce))
                                        } catch (e: Exception) {
                                            // Handle resolution exceptions (e.g., for reflection calls)
                                            // println("Could not resolve method call: ${mce}")
                                        }
                                    }
                                }, null)
                            }
                        }, null)
                    
                        
                    }
                }
            
                return callGraph.toList()
            }

            fun splitLogicalExpression(expression: Expression): List<Expression> {
                val expressions = mutableListOf<Expression>()
                if (expression is BinaryExpr) {
                    when (expression.operator) {
                        BinaryExpr.Operator.OR, BinaryExpr.Operator.AND -> {
                            expressions.addAll(splitLogicalExpression(expression.left))
                            expressions.addAll(splitLogicalExpression(expression.right))
                        }
                        else -> expressions.add(expression)
                    }
                } else {
                    expressions.add(expression)
                }
                return expressions
            }

            fun findConditionByLineAndPos(filePath: String, lineNumber: Int, positionNumber: Int):String {
                val sourceFile = File(filePath)
                var currentCount = 0
                val cu: CompilationUnit? = javaParser.parse(sourceFile).result.orElse(null)
                var condition: Expression? = null
                cu?.accept(object : VoidVisitorAdapter<Void>() {
                    override fun visit(n: IfStmt, arg: Void?) {
                        super.visit(n, arg)
                        if (n.begin.isPresent && n.begin.get().line == lineNumber) {
                            condition = splitLogicalExpression(n.condition)[positionNumber]
                        }
                    }
                }, null)
        
                // 如果找到了条件表达式，返回其字符串表示
                return condition?.toString() ?: ""

            }

            fun findMehodByLineAndPos(filePath: String, lineNumber: Int, positionNumber: Int):String {
                val sourceFile = File(filePath)

                var methodCalls = mutableListOf<MethodCallExpr>()
            
                try {
                    val cu: CompilationUnit? = javaParser.parse(sourceFile).result.orElse(null)
                    cu?.accept(object : VoidVisitorAdapter<Void?>() {
                        override fun visit(n: MethodCallExpr, arg: Void?) {
                            super.visit(n, arg)
                            if (n.begin.isPresent && n.begin.get().line == lineNumber) {
                                methodCalls.add(n)
                            }
                        }
                    }, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return methodCalls[positionNumber].toString()
            }

            fun getFunctionCode(filePath: String, lineNumber: Int): Pair<String?, String?> {
                val file = File(filePath)
                if (!file.exists()) return Pair(null, null)

                val parseResult = javaParser.parse(file)
                if (!parseResult.isSuccessful) return Pair(null, null)
            
                val compilationUnit = parseResult.result.get()
                var extractedMethod: String? = null
                var extractedMethodName: String? = null
                var found = false
                compilationUnit.accept(object : VoidVisitorAdapter<Void?>() {
                    override fun visit(method: MethodDeclaration, arg: Void?) {
                        if(found) return 
                        method.range.ifPresent { range ->
                            if (lineNumber in range.begin.line..range.end.line) {
                                extractedMethod = method.toString()
                                extractedMethodName = method.getSignature().asString()
                                try {  
                                    extractedMethodName = method.resolve().qualifiedSignature
                                } catch (e: Exception) {
                                }
                                found = true  
                            }
                        }
                        if (!found) super.visit(method, arg)  // 继续遍历
                    }
                }, null)
            
                return Pair(extractedMethodName, extractedMethod)
            }

            fun getMethodSignature(md: MethodDeclaration): String {
                val annotations = md.annotations.joinToString(" ") { it.toString() }
                val modifiers = md.modifiers.joinToString(" ") { it.keyword.asString() }
                val returnType = md.type.toString()
                val methodName = md.nameAsString
                val parameters = md.parameters.joinToString(", ") { param ->
                    val paramAnnotations = param.annotations.joinToString(" ") { it.toString() }
                    "$paramAnnotations ${param.type} ${param.name}"
                }
                return "$annotations $modifiers $returnType $methodName($parameters)"
            }


            fun extractContextForMethodCallExpr(callExprs: List<Pair<MethodDeclaration,MethodCallExpr>>): String{
                val allcall = mutableListOf<String>()
                callExprs.forEach  { methodCall ->
                    val arguments = methodCall.second.arguments
                    val sb = StringBuilder()
                    val resolvedMethod = methodCall.second.resolve()
                    val numberOfParams = resolvedMethod.numberOfParams
                    sb.append("${getMethodSignature(methodCall.first)} call ${methodCall.second}\n")
                    sb.append("Arguments:\n")
                    var i = 0
                    arguments.forEach { argExpr ->
                        val visitedVariables = HashSet<String>()
                        // sb.append(" - $argExpr\n")
                        val variableDefinitions = mutableListOf<Node>()
                        argExpr.findAncestor(CompilationUnit::class.java).ifPresent { cu ->
                            findUsagesAndDefinitions(argExpr, cu, variableDefinitions, visitedVariables)
                        }
                        sb.append("${resolvedMethod.getParam(i).name} = ${arguments[i]}\n")
                        sb.append(variableDefinitions.joinToString(separator = "\n") { getVariableDeclaration(it) })
                        sb.append("\n")
                        i++
                    }
                    allcall.add(sb.toString())
                }
                return allcall.asReversed().joinToString(separator = "")
            }

            fun findCallFunctionByName(functionName: String, all: MutableList<Pair<MethodDeclaration,MethodCallExpr>>) {
                var matchingCalls = callGraph.filter { it.callee == functionName }
                if (matchingCalls.isEmpty()){
                    matchingCalls = callGraph.filter { it.call.nameAsString == functionName }
                }
            
                matchingCalls.forEach { call ->
                    //println("Caller: ${call.caller}, Callee: ${call.callee}, File: ${call.fileName}")
                    if (!all.contains(Pair(call.del,call.call))&& all.size<=10) {
                        all.add(Pair(call.del,call.call))
                        findCallFunctionByName(call.caller, all)
                        findCallFunctionByName(call.del.nameAsString, all)
                    }
                    
                    
                }
            }



            fun findFunctionByLine(filePath: String, lineNumber: Int):List<MethodDeclaration> {
                val sourceFile = File(filePath)

                var methodDefinitions = mutableListOf<MethodDeclaration>()
            
                try {
                    val cu: CompilationUnit? = javaParser.parse(sourceFile).result.orElse(null)
                    cu?.accept(object : VoidVisitorAdapter<Void?>() {
                        override fun visit(n: MethodCallExpr, arg: Void?) {
                            super.visit(n, arg)

                            if (n.begin.isPresent && n.begin.get().line == lineNumber) {
                                try{
                                    val resolvedMethod: ResolvedMethodDeclaration = n.resolve()
                                    //println("Method Call: ${n}")
                                    //println("Declaration: ${resolvedMethod.qualifiedSignature}")
                                    resolvedMethod.toAst().ifPresent { methodDeclaration ->
                                        //println("Method Code:\n${methodDeclaration}")
                                        methodDefinitions.add(methodDeclaration)
                                    }
                                }catch(e: Exception) {
                                    // e.printStackTrace()
                                }  
                            }
                        }
                    }, null)
                } catch (e: Exception) {
                    // e.printStackTrace()
                }

                return methodDefinitions.distinct()
            }

            fun findVariableDefinitions(cu: CompilationUnit, variableName: String, variableDefinitions: MutableList<Node>, variableNames: HashSet<String>) {
                if (variableNames.contains(variableName)) return
                variableNames.add(variableName)
                cu.findAll(VariableDeclarator::class.java).forEach { varDecl ->
                    if (varDecl.nameAsString == variableName) {
                        variableDefinitions.add(varDecl)
                        //println(varDecl.toString())
                        // Recursively find definitions for the right-hand side (RHS) variables
                        varDecl.initializer.ifPresent { rhs ->
                            findUsagesAndDefinitions(rhs, cu, variableDefinitions, variableNames)
                        }
                    }
                }

                cu.findAll(AssignExpr::class.java).forEach { assignExpr ->
                    if ((assignExpr.target as? NameExpr)?.nameAsString == variableName) {
                        variableDefinitions.add(assignExpr)
                        //println(assignExpr.toString())
                        // Recursively find definitions for the right-hand side (RHS) variables
                        findUsagesAndDefinitions(assignExpr.value, cu, variableDefinitions, variableNames)
                    }
                }
            }

            fun findUsagesAndDefinitions(expression: Expression, cu: CompilationUnit, variableDefinitions: MutableList<Node>, variableNames: HashSet<String>) {
                expression.findAll(NameExpr::class.java).forEach { nameExpr ->
                    val variableName = nameExpr.nameAsString
                    if (!variableNames.contains(variableName)) {
                        nameExpr.findAncestor(CompilationUnit::class.java).ifPresent { ancestorCu ->
                            findVariableDefinitions(ancestorCu, variableName, variableDefinitions, variableNames)
                        }
                    }
                    //if(!variableNames.contains(variableName))findVariableDefinitions(cu, variableName, variableDefinitions, variableNames)
                }
            }

            fun getVariableDefinitions(filePath: String, lineNumber: Int, position: Int): List<Node> {
                val sourceFile = File(filePath)

                val cu: CompilationUnit = javaParser.parse(sourceFile).result.get()

                val variableDefinitions = mutableListOf<Node>()

                val usages = mutableListOf<NameExpr>()
                cu.accept(object : VoidVisitorAdapter<Void?>() {
                    override fun visit(nameExpr: NameExpr, arg: Void?) {
                        super.visit(nameExpr, arg)
                        val range = nameExpr.range
                        if (range.isPresent && range.get().begin.line == lineNumber) {
                            usages.add(nameExpr)
                        }
                    }
                }, null)

                val variableNames = HashSet<String>()

                for (usage in usages) {
                    try {
                        val variableName = usage.nameAsString
                        // val variableDeclaration: ResolvedValueDeclaration = usage.resolve()
                        // System.out.println("Variable Name: " + variableDeclaration.getName());
                        // System.out.println("Type: " + variableDeclaration.getType().describe());
                        // System.out.println("Is Field: " + variableDeclaration.isField());
                        findVariableDefinitions(cu, variableName, variableDefinitions, variableNames)
                    } catch (e: Exception) {
                         // e.printStackTrace()
                    }
                }

                return variableDefinitions.distinct()
            }

            fun getLineCode(filePath: String, lineNumber: Int): String? {
                val path = Paths.get(filePath)
                if (!Files.exists(path)) {
                    return null
                }
            
                var result: String? = null
                BufferedReader(Files.newBufferedReader(path)).use { reader ->
                    var currentLine = 0
                    reader.forEachLine { line ->
                        currentLine++
                        if (currentLine == lineNumber) {
                            result = line
                            return@forEachLine
                        }
                    }
                }
                return result
            }

            fun extractElement(packageName: String, position: Int): String? {
                val parts = packageName.split('.')
                return if (parts.size > position) parts[position] else null
            }

            fun getVariableDeclaration(node: Node): String {
                return when (node) {
                    is VariableDeclarator -> {
                        val parent = node.parentNode.get()
                        val initializer = node.initializer.orElse(null)
                        val initializerStr = if (initializer != null) " = $initializer" else ""
                        when (parent) {
                            is FieldDeclaration -> "${parent.commonType} ${node.name}$initializerStr"
                            is VariableDeclarationExpr -> "${parent.commonType} ${node.name}$initializerStr"
                            else -> node.toString()
                        }
                    }
                    is AssignExpr -> {
                        val target = node.target
                        val value = node.value
                        "$target = $value"
                    }
                    else -> node.toString()
                }
            }            

            fun searchForCode(target: String, enableLLMAssistedRequestGeneration: Boolean, enableValueExpansion: Boolean): Pair<String?, String?>{
                var repoPath = repoPathsArray[0]

                val fileExtension = ".java" 
                // "Branch_at_org.zalando.catwatch.backend.web.ContributorsApi_at_line_00215_position_0_falseBranch"
                // "MethodReplacement_at_org.cbioportal.genome_nexus.service.internal.BaseVariantAnnotationServiceImpl_00329_0_BOOLEAN_true"
                val regex = Regex("Branch_at_(.+?)_at_line_(\\d+)_position_(\\d+)_(false|true)Branch")
                val regex2 = Regex("MethodReplacement_at_(.+?)_(\\d+)_(\\d+)_BOOLEAN_(true|false)")
                val regex3 = Regex("NumericComparison_(.+?)_(\\d+)_(\\d+)_(\\w+)")
                val matchResult = regex.find(target)
                val matchResult2 = regex2.find(target)
                val matchResult3 = regex3.find(target)
                if (matchResult != null) {
                    val (fileName, lineNumber, position, booleanValue) = matchResult.destructured               
                    val lineNumberInt = lineNumber.toInt()
                    val positionInt = position.toInt()
                    val filePath = fileName?.replace('.', '/') + fileExtension
                    
                    var isExist = false
                    var fullFilePath = ""
                    for(iRepoPath in repoPathsArray){
                        val tempfullFilePath = "$iRepoPath/$filePath"
                        val file = File(tempfullFilePath)
                        if (file.exists()){
                            fullFilePath = "$iRepoPath/$filePath"
                            repoPath = iRepoPath
                            isExist = true
                            break
                        }
                    }
                    if(!isExist){
                        return Pair(null, null)
                    }

                    val typeSolver = CombinedTypeSolver(
                        ReflectionTypeSolver(),
                        JavaParserTypeSolver(File(repoPath))
                    )
                    val parserConfiguration = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
                    javaParser = JavaParser(parserConfiguration)


                    var functionCode: String? = null
                    var functionName: String? = null
                    try {
                        var functionInfo = getFunctionCode(fullFilePath, lineNumberInt)
                        functionCode = functionInfo.second
                        functionName = functionInfo.first
                    } catch (e: IOException) {
                        // e.printStackTrace()
                    }

                    var lineCode = getLineCode(fullFilePath, lineNumberInt)
                    if(!enableLLMAssistedRequestGeneration)return Pair(functionCode, lineCode)
                    var targetCond : String  = ""
                    try{
                        targetCond = findConditionByLineAndPos(fullFilePath, lineNumberInt, positionInt)
                    }catch (e: Exception){
                        targetCond = "${positionInt}rd"
                    }
                    if(targetCond=="")targetCond = "${positionInt}rd"
                    
                    if(lineCode!=null){
                        val comment = "     Our target is ${targetCond} branch condition (Instead of the whole branch condition!) in the line, its value should be $booleanValue"
                        lineCode += comment
                        if(enableValueExpansion){
                            val typeSolver = CombinedTypeSolver(
                                ReflectionTypeSolver()
                            )
            
                            for (path in repoPathsArray) {
                                typeSolver.add(JavaParserTypeSolver(File(path)))
                            }            
                        
                            val parserConfiguration = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
                            javaParser = JavaParser(parserConfiguration)
                            // val sourceFile = File(fullFilePath)
                            // cu = javaParser.parse(sourceFile).result.get()

                            var methodCalls = mutableListOf<Pair<MethodDeclaration,MethodCallExpr>>()
                            if(functionName!=null){
                                findCallFunctionByName(functionName, methodCalls)
                            }
                            
                            val callmethodInfo = extractContextForMethodCallExpr(methodCalls.distinct())
                            functionCode = callmethodInfo + functionCode
                            val variableDefinitions = getVariableDefinitions(fullFilePath, lineNumberInt, positionInt)
                            val defUseInfo = variableDefinitions.joinToString(separator = "\n") { getVariableDeclaration(it) }
                            val submethodInfo = findFunctionByLine(fullFilePath, lineNumberInt).joinToString(separator = "\n") {  it.toString() }
                            if(defUseInfo!="")lineCode += "\ndef-use analysis of the line is\n$defUseInfo "
                            if(submethodInfo!="")lineCode +="\ncalled function definition of the line is:\n$submethodInfo\n"
                        }

                    }

                    //LoggingUtil.getInfoLogger().info("Line code {}",lineCode)
                    //LoggingUtil.getInfoLogger().info("Function code {}",functionCode)
                    
                    return Pair(lineCode, functionCode)
                }
                else if (matchResult2 != null){
                    val (fileName, lineNumber, position, booleanValue) = matchResult2.destructured
                    val lineNumberInt = lineNumber.toInt()
                    var positionInt = position.toInt()
                    val filePath = fileName?.replace('.', '/') + fileExtension
                    var isExist = false
                    var fullFilePath = ""
                    
                    for(iRepoPath in repoPathsArray){
                        val tempfullFilePath = "$iRepoPath/$filePath"
                        val file = File(tempfullFilePath)
                        if (file.exists()){
                            fullFilePath = "$iRepoPath/$filePath"
                            repoPath = iRepoPath
                            isExist = true
                            break
                        }
                    }
                    if(!isExist){
                        return Pair(null, null)
                    }

                    val typeSolver = CombinedTypeSolver(
                        ReflectionTypeSolver(),
                        JavaParserTypeSolver(File(repoPath))
                    )
                    val parserConfiguration = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
                    javaParser = JavaParser(parserConfiguration)

                    var functionCode: String? = null
                    var functionName: String? = null
                    try {
                        var functionInfo = getFunctionCode(fullFilePath, lineNumberInt)
                        functionCode = functionInfo.second
                        functionName = functionInfo.first
                    } catch (e: IOException) {
                        // e.printStackTrace()
                    }

                    var lineCode = getLineCode(fullFilePath, lineNumberInt) 
                    if(!enableLLMAssistedRequestGeneration)return Pair(functionCode, lineCode)
                    var targetMethod : String  = ""
                    try{
                        targetMethod = findMehodByLineAndPos(fullFilePath, lineNumberInt, positionInt)
                    }catch (e: Exception){
                        targetMethod = "${positionInt}rd"
                    }
    
                    if(lineCode!=null){
                        val comment = "     Our target is successfully calling the submethod $targetMethod and let it return $booleanValue. "
                        lineCode += comment

                        if(enableValueExpansion){
                            val typeSolver = CombinedTypeSolver(
                                ReflectionTypeSolver()
                            )
            
                            for (path in repoPathsArray) {
                                typeSolver.add(JavaParserTypeSolver(File(path)))
                            }            
                        
                            val parserConfiguration = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
                            javaParser = JavaParser(parserConfiguration)

                            var methodCalls = mutableListOf<Pair<MethodDeclaration,MethodCallExpr>>()
                            if(functionName!=null){
                                findCallFunctionByName(functionName, methodCalls)
                            }

                            val callmethodInfo = extractContextForMethodCallExpr(methodCalls.distinct())
                            functionCode = callmethodInfo + functionCode
                            val variableDefinitions = getVariableDefinitions(fullFilePath, lineNumberInt, positionInt)
                            val defUseInfo = variableDefinitions.joinToString(separator = "\n") { getVariableDeclaration(it) }
                            val submethodInfo = findFunctionByLine(fullFilePath, lineNumberInt).joinToString(separator = "\n") {  it.toString() }
                            if(defUseInfo!="")lineCode += "\ndef-use analysis of the line is\n$defUseInfo "
                            if(submethodInfo!="")lineCode +="\ncalled function definition of the line is:\n$submethodInfo\n"
                        }
                    }

                    return Pair(lineCode, functionCode)
                }
                return Pair(null,null)
            }

            fun LLMCall(prompt:String): String?{
                var messages = mutableListOf<ChatMessage>() 
                val systemMessage = ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)
                messages.add(systemMessage)
                try {
                val completionRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o")
                    .messages(messages)
                    .n(1)
                    .maxTokens(2048)
                    .logitBias(emptyMap()) 
                    .build()
                    
                val completionRes = service.createChatCompletion(completionRequest).getChoices()
                // completionRes.forEach(System.out::println);
                return completionRes[0].message.content

                } catch (e: SocketTimeoutException) {
                    //LoggingUtil.getInfoLogger().error("Request timed out. Please try again later.")
                    return null 
            
                } catch (e: Exception) {
                    //LoggingUtil.getInfoLogger().error("An unexpected error occurred: ${e.message}")
                    return null 

                }   finally {
                    service.shutdownExecutor() 
                }
            }

            fun removeComments(jsonString: String): String {
                // Regular expression to match single-line and multi-line comments
                val regex = """(?s)//.*?\n|/\*.*?\*/""".toRegex()
                return regex.replace(jsonString) { matchResult ->
                    // Preserve newlines for single-line comments to avoid breaking JSON structure
                    if (matchResult.value.startsWith("//")) "\n" else ""
                }
            }
            
            
            fun extractMock(mockedResponse: String): Map<String, String>?{
                var jsonString = mockedResponse.substringAfter("----outcome start----")
                            .substringBefore("----outcome end----")
                            .trim()

                if (jsonString.startsWith("```json")) {
                    jsonString = jsonString.removePrefix("```json").trim()
                }
                if (jsonString.endsWith("```")) {
                    jsonString = jsonString.removeSuffix("```").trim()
                }
                // jsonString = removeComments(jsonString)

                try {
                    if (jsonString != "") {
                        val jsonObject = JSONObject(jsonString)

                        val name = jsonObject.get("name").toString()
                        val url = jsonObject.get("url").toString()
                        val responseCode = jsonObject.get("response_code").toString()
                        val responseBody = jsonObject.get("response_body").toString()

                        return mapOf(
                        "name" to name,
                        "url" to url,
                        "response_code" to responseCode,
                        "response_body" to responseBody
                        )
                    } 
                }
                catch (e: Exception) {
                        return null
                }

                return null
            }

            fun extractRequest(mockedResponse: String): Map<String, String>?{
                var jsonString = mockedResponse.substringAfter("----outcome start----")
                            .substringBefore("----outcome end----")
                            .trim()

                if (jsonString.startsWith("```json")) {
                    jsonString = jsonString.removePrefix("```json").trim()
                }
                if (jsonString.endsWith("```")) {
                    jsonString = jsonString.removeSuffix("```").trim()
                }
                var jsonString2 = mockedResponse.substringAfter("```json")
                .substringBefore("```")
                .trim()

                // println("jsonString $jsonString")
                // println("jsonString2 $jsonString2")

                try {
                    if (jsonString != "") {
                        val jsonObject = JSONObject(jsonString)

                        val index = jsonObject.get("index").toString()
                        val paramKey = jsonObject.get("field_name").toString()
                        val paramValue = jsonObject.get("field_value").toString()
                        val paramType = jsonObject.get("param_type").toString()

                        return mapOf(
                        "index" to index,
                        "field_name" to paramKey,
                        "field_value" to paramValue,
                        "param_type" to paramType,
                        )
                    }
                }                
                catch (e: Exception) {
                }
    
                try {
                    if(jsonString2 != ""){
                        val jsonObject = JSONObject(jsonString2)

                        val index = jsonObject.get("index").toString()
                        val paramKey = jsonObject.get("field_name").toString()
                        val paramValue = jsonObject.get("field_value").toString()
                        val paramType = jsonObject.get("param_type").toString()

                        return mapOf(
                        "index" to index,
                        "field_name" to paramKey,
                        "field_value" to paramValue,
                        "param_type" to paramType,
                        )
                    } 
                }
                catch (e: Exception) {
                }

                return null
            }

            fun generateHintRequest(target: String, relatedCode: Pair<String?, String?>, restCallActionInfo: String, hints: String): Map<String, String>?{
                var prompt = 
"""You are an API tester, doing automated request generation.  You should:
First, based on field in request and target code, select related Rest Call Action RCA.
Then, identify which type of param is related. Also, which field of the param of the RCA will affect the execution path to cover the target.
Finally, revise the field inside the param.
Note that not only you should satisfy the condition in target line, but also you should ensure the request can execute to 1. target line 2.specific position in the target line. So extract the precondition first, and ensure it is satisfied when you genrate hint.

We accept feedback from the execution outcome of last generated outputs, which are false answers, don't repeat, including: 
$hints

Output a json like following (without comment inside like //):
----outcome start----
{"index":{}, "field_name":{}, "field_value":{}, "param_type": {body/query/path/header}}
----outcome end----
For example,
example 1 is 
{"index":0, "field_name":"txt", "field_value":"a", "param_type":"path"}
example 2 is 
{"index":0, "field_name":"", "field_value":{"dayname":"fri","monthname":"jan"}, "param_type":"path"}
example 3 is
{"index":0, "field_name":"", "field_value":[{"start":802,"end":164},{"start":84,"end":117}], "param_type":"body"} or

Note that for body type, field_name is empty, field_value should be a json including all fields.

Target is $target

Target line code is ${relatedCode.first} 

Target line is inside below function:
${relatedCode.second}

Rest Call Action include:
$restCallActionInfo 
""".trimIndent()
//Note that param_name is empty for body type. You can add new key-value inside the body parm. But for GET verb, body param is null.
//Note that index should be a number, for type query, path and header: param_name should be specific key and param_value should be coresponding value, while for type body: param_name is empty, and param_value should belike map like {"key1":"value1","key2","value2"}.
                val res = LLMCall(prompt)
                if(res == null){
                    return null
                }

                return extractRequest(res)
            }


            fun generateHint(target: String, relatedCode: Pair<String?, String?>, externalserviceActionInfo: String): Map<String, String>?{
                var prompt = 
"""You are an API tester, for line ${relatedCode.first} 
You are doing automated mocking.  You should 
First, based on field in response and target code, select related External service Action RESA.
Then, identify which field in the response body of the RESA will affect the execution path to cover the target.
Finally, revise the field inside response body and response code, get a new full response as the mocked response.

You only need to output a json including the url of related action and its mocked response as follow (without comment inside like //):
----mocked response start----
{name:{}, url:{}, response_code:{}, response_body:{}}
----mocked response end----

Target is $target

Target line is inside below function:
${relatedCode.second}

External service action include:
$externalserviceActionInfo 
""".trimIndent()
                val res = LLMCall(prompt)
                if(res == null){
                    return null
                }

                return extractMock(res)
            }
        }
    }
