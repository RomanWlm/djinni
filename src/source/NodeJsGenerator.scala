package djinni

import djinni.ast._
import djinni.generatorTools._
import djinni.meta._

import scala.collection.mutable

class NodeJsGenerator(spec: Spec, helperFiles: NodeJsHelperFilesDescriptor) extends Generator(spec) {

  protected val marshal = new NodeJsMarshal(spec)
  protected val cppMarshal = new CppMarshal(spec)

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface): Unit = {

    val isNodeMode = true
    //Generate header
    generateInterface(origin, ident, doc, typeParams, i, isNodeMode)

    //Generate implementation file
    val baseClassName = marshal.typename(ident, i)

    if (i.ext.nodeJS) {

      val fileName = idNode.ty(ident.name) + ".cpp"
      createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>

        w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
        w.wl("// This file generated by Djinni from " + origin)

        val hppFileName = "#include \"" + idNode.ty(ident.name) + "." + spec.cppHeaderExt + "\""
        w.wl
        w.wl(hppFileName)
        w.wl("#include \""+ helperFiles.ObjectWrapperHeader + "\"")
        w.wl
        w.wl("using namespace v8;")
        w.wl("using namespace node;")
        w.wl("using namespace std;")

        for (m <- i.methods) {

          val ret = cppMarshal.returnType(m.ret)
          val methodName = m.ident.name
          val params = m.params.map(p => cppMarshal.paramType(p.ty.resolved) + " " + idNode.local(p.ident))
          if (!m.static) {
            val constFlag = if (m.const) " const" else ""
            w.wl
            w.wl(s"$ret $baseClassName::$methodName${params.mkString("(", ", ", ")")}$constFlag").braced {
              w.wl("Nan::HandleScope scope;")
              /*
                Special treatment for Callbacks
                We consider "Callback" a keyword to be contained in all callback objects,
                they will implement only a "onCallback" method meeting this signature (idl format) :
                template <typename T, typename S>
                onCallback(result: optional<T>, error: optional<S>);

                WARNING: Be sure to respect arguments' number and order
               */

              val isCallback = methodName.contains("onCallback") &&
                idNode.ty(ident.name).contains("Callback") &&
                (m.params.length == 2)

              w.wl("//Wrap parameters")
              val countArgs = checkAndCastTypes(ident, i, m, w)

              if (isCallback) {

                val errorName = m.params(1).ident.name

                w.wl("auto local_resolver = Nan::New<Promise::Resolver>(pers_resolver);")
                w.wl(s"if($errorName)").braced {
                  w.wl("auto rejected = local_resolver->Reject(Nan::GetCurrentContext(), arg_1);")
                  w.wl("rejected.FromJust();")
                }
                w.wl(s"else").braced {
                  w.wl("auto resolve = local_resolver->Resolve(Nan::GetCurrentContext(), arg_0);")
                  w.wl("resolve.FromJust();")
                }

              } else {
                //Windows complains about 0 sized arrays
                val arraySize = if(countArgs == 0) 1 else countArgs
                var args: String = s"Handle<Value> args[$arraySize"
                if(countArgs > 0) {
                  args = s"${args}] = {"
                  for (i <- 0 to countArgs - 1) {
                    args = s"${args}arg_$i"
                    if (i < m.params.length - 1) {
                      args = s"${args},"
                    }
                  }
                  w.wl(s"${args}};")
                } else {
                  w.wl(s"${args}];")
                }

                //Get local from persistent
                w.wl("Local<Object> local_njs_impl = Nan::New<Object>(njs_impl);")
                w.wl("if(!local_njs_impl->IsObject())").braced {
                  val error = s""""$baseClassName::$methodName fail to retrieve node implementation""""
                  w.wl(s"Nan::ThrowError($error);")
                }

                val quotedMethod = s""""$methodName""""
                w.wl(s"auto calling_funtion = Nan::Get(local_njs_impl,Nan::New<String>($quotedMethod).ToLocalChecked()).ToLocalChecked();")
                w.wl(s"auto result_$methodName = Nan::CallAsFunction(calling_funtion->ToObject(),local_njs_impl,$countArgs,args);")
                w.wl(s"if(result_$methodName.IsEmpty())").braced {
                  val error = s""""$baseClassName::$methodName call failed""""
                  w.wl(s"Nan::ThrowError($error);")
                }

                if (m.ret.isDefined && ret != "void") {
                  w.wl(s"auto checkedResult_$methodName = result_$methodName.ToLocalChecked();")
                  marshal.toCppArgument(m.ret.get.resolved, s"fResult_$methodName", s"checkedResult_$methodName", w)
                  w.wl(s"return fResult_$methodName;")
                }
              }
            }
          }
        }
        w.wl
        createNanNewMethod(ident, i, None, w)
        w.wl
        createInitializeMethod(ident, i, w)
      })
    }
  }

  protected def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface, nodeMode: Boolean): Unit = {

    val refs = new CppRefs(ident.name)
    i.methods.map(m => {
      m.params.map(p => refs.find(p.ty, true, nodeMode))
      m.ret.foreach((x) => refs.find(x, true, nodeMode))
    })

    if (refs.hpp("#include <memory>") &&
      refs.cpp("#include <memory>")) {
      refs.cpp.remove("#include <memory>")
    } else if (!nodeMode &&
      //For C++ interfaces we always have shared_ptr for c++ implementation member
      !refs.hpp("#include <memory>") &&
      !refs.cpp("#include <memory>")) {
      refs.hpp.add("#include <memory>")
    }

    val baseClassName = marshal.typename(ident, i)
    val cppClassName = cppMarshal.typename(ident, i)
    val className = baseClassName

    //Create .hpp file
    val cppInterfaceHpp = "\"" + spec.nodeIncludeCpp + "/" + ident.name + "." + spec.cppHeaderExt + "\""
    val cpp_shared_ptr = "std::shared_ptr<" + spec.cppNamespace + "::" + cppClassName + ">"

    val define = ("DJINNI_GENERATED_" + spec.nodeFileIdentStyle(ident.name) + "_" + spec.cppHeaderExt).toUpperCase

    if ((i.ext.nodeJS && nodeMode) || (i.ext.cpp && !nodeMode)) {

      var fileName = if (nodeMode) idNode.ty(ident.name) else idNode.ty(ident.name).concat("Cpp")
      fileName = s"$fileName.${spec.cppHeaderExt}"

      createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>


        w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
        w.wl("// This file generated by Djinni from " + origin)
        w.wl
        w.wl(s"#ifndef $define")
        w.wl(s"#define $define")
        w.wl

        //Include hpp refs
        if (refs.hpp.nonEmpty) {
          w.wl
          refs.hpp.foreach(w.wl)
        }

        //Include cpp refs
        if (refs.cpp.nonEmpty) {
          w.wl
          refs.cpp.foreach(w.wl)
        }

        w.wl
        w.wl("#include <nan.h>")
        w.wl("#include <node.h>")
        w.wl(s"#include $cppInterfaceHpp")
        w.wl
        w.wl("using namespace v8;")
        w.wl("using namespace node;")
        w.wl("using namespace std;")
        w.wl(s"using namespace ${spec.cppNamespace};")

        if (i.ext.nodeJS && refs.hppFwds.nonEmpty) {
          w.wl
          refs.hppFwds.foreach(w.wl)
        }

        var classInheritance = s"class $className final"
        if (nodeMode) {
          classInheritance = s"class $className: public ${spec.cppNamespace}::$cppClassName"
        }
        w.wl
        w.w(classInheritance).bracedSemi {

          //Callbacks always treated differently
          var isCallback = false
          if(ident.name.contains("Callback")) {
            for (m <- i.methods) {
              val methodName = m.ident.name
              if(methodName.contains("onCallback") && (m.params.length == 2)) {
                isCallback = true
              }
            }
          }

          w.wlOutdent("public:")
          w.wl
          w.wl(s"static void Initialize(Local<Object> target);")

          if (!nodeMode) {
            //Constructor
            w.wl(s"$className() = delete;")

            //Object prototype and static wrap method (from c++ to v8/Nan object)
            w.wl
            w.wl(s"static Local<Object> wrap(const $cpp_shared_ptr &object);")
            w.wl(s"static Nan::Persistent<ObjectTemplate> ${cppClassName}_prototype;")
          } else {

            // Destructor
            w.wl(s"~$className()").bracedSemi {
              if (isCallback) {
                w.wl("pers_resolver.Reset();")
              } else {
                w.wl("njs_impl.Reset();")
              }
            }

            //Constructor
            if (isCallback){
              w.wl(s"$className(Local<Promise::Resolver> resolver){pers_resolver.Reset(resolver);};")
            } else {
              w.wl(s"$className(Local<Object> njs_implementation){njs_impl.Reset(njs_implementation);};")
            }

            //For node implementation, use C++ types
            for (m <- i.methods) {
              val ret = cppMarshal.returnType(m.ret)
              val methodName = m.ident.name
              val params = m.params.map(p => cppMarshal.paramType(p.ty.resolved) + " " + idNode.local(p.ident))
              if (!m.static) {
                val constFlag = if (m.const) " const" else ""
                w.wl
                writeDoc(w, m.doc)
                w.wl(s"$ret $methodName${params.mkString("(", ", ", ")")}$constFlag;")
              }
            }

          }
          w.wl
          // Methods
          w.wlOutdent("private:")
          for (m <- i.methods) {
            val methodName = m.ident.name
              writeDoc(w, m.doc)
              w.wl(s"static NAN_METHOD($methodName);")
              w.wl
          }
          //Add declaration of New (Nan) method
          w.wl(s"static NAN_METHOD(New);")
          w.wl
          if (!nodeMode) {
            //Implementation in C++
            w.wl(s"static NAN_METHOD(isNull);")
          } else {
            //Persistent promise
            if (isCallback) {
              w.wl("Nan::Persistent<Promise::Resolver> pers_resolver;")
            } else {
              w.wl("Nan::Persistent<Object> njs_impl;")
            }

          }
        }
        w.wl(s"#endif //$define")
      })
    }
  }

  protected def createNanNewMethod(ident: Ident, i: Interface, factory: Option[Interface.Method], wr: writer.IndentWriter): Unit = {

    val baseClassName = marshal.typename(ident, i)
    val cppClassName = cppMarshal.typename(ident, i)

    wr.w(s"NAN_METHOD($baseClassName::New)").braced {

      wr.wl("//Only new allowed")
      wr.wl("if(!info.IsConstructCall())").braced {
        val error = s""""$baseClassName function can only be called as constructor (use New)""""
        wr.wl(s"return Nan::ThrowError($error);")
      }

      //TODO: if no factory ?
      if (factory.isDefined) {

        val factoryName = factory.get.ident.name
        val factoryArgsLength = factory.get.params.length

        wr.wl
        wr.wl(s"//Check if $baseClassName::New called with right number of arguments")
        wr.wl(s"if(info.Length() != $factoryArgsLength)").braced {
          val error = s""""$baseClassName::New needs same number of arguments as ${spec.cppNamespace}::$cppClassName::$factoryName method""""
          wr.wl(s"return Nan::ThrowError($error);")
        }

        //TODO: create an unwrap function
        wr.wl
        wr.wl("//Unwrap objects to get C++ classes")
        val countFactoryArgs = checkAndCastTypes(ident, i, factory.get, wr)
        var factoryArgs: String = ""
        for (i <- 0 to countFactoryArgs - 1) {
          factoryArgs = s"${factoryArgs}arg_$i"
          if (i < factory.get.params.length - 1) {
            factoryArgs = s"$factoryArgs,"
          }
        }

        wr.wl
        wr.wl("//Call factory")
        wr.wl(s"auto cpp_instance = ${spec.cppNamespace}::$cppClassName::$factoryName($factoryArgs);")
        wr.wl(s"djinni::js::ObjectWrapper<${spec.cppNamespace}::$cppClassName>::Wrap(cpp_instance, info.This());")
      }

      if (i.ext.nodeJS) {
        //If callback instanciate a Resolver
        //auto arg_1_resolver = v8::Promise::Resolver::New(Nan::GetCurrentContext()).ToLocalChecked();
        wr.wl
        if (ident.name.contains("Callback")){
          wr.wl("auto resolver = v8::Promise::Resolver::New(Nan::GetCurrentContext()).ToLocalChecked();")
          wr.wl(s"auto node_instance = std::make_shared<$baseClassName>(resolver);")
        } else {
          wr.wl("if(!info[0]->IsObject())").braced {
            val error = s""""$baseClassName::New requires an implementation from node""""
            wr.wl(s"return Nan::ThrowError($error);")

          }
          wr.wl(s"auto node_instance = std::make_shared<$baseClassName>(info[0]->ToObject());")
        }
        wr.wl(s"djinni::js::ObjectWrapper<$baseClassName>::Wrap(node_instance, info.This());")
      }
      wr.wl("info.GetReturnValue().Set(info.This());")
    }
  }

  protected def checkAndCastTypes(ident: Ident, i: Interface, method: Interface.Method, wr: writer.IndentWriter): Int = {

    var count = 0
    method.params.map(p => {
      val index = method.params.indexOf(p)
      if (i.ext.cpp) {
        marshal.toCppArgument(p.ty.resolved, s"arg_$index", s"info[$index]", wr)
      } else {
        marshal.fromCppArgument(p.ty.resolved, s"arg_$index", idNode.local(p.ident), wr)
      }
      count = count + 1
    })
    count
  }

  protected def createInitializeMethod(ident: Ident, i: Interface, wr: writer.IndentWriter): Unit = {

    val baseClassName = marshal.typename(ident, i)
    wr.w(s"void $baseClassName::Initialize(Local<Object> target)").braced {
      wr.wl("Nan::HandleScope scope;")
      wr.wl
      wr.wl(s"Local<FunctionTemplate> func_template = Nan::New<FunctionTemplate>($baseClassName::New);")
      wr.wl("Local<ObjectTemplate> objectTemplate = func_template->InstanceTemplate();")
      wr.wl("objectTemplate->SetInternalFieldCount(1);")
      val quotedClassName = "\"" + baseClassName + "\""
      wr.wl
      wr.wl(s"func_template->SetClassName(Nan::New<String>($quotedClassName).ToLocalChecked());")
      if (i.ext.cpp) {
        wr.wl
        wr.wl(s"//SetPrototypeMethod all methods")
        for (m <- i.methods) {
          //if (!m.static) {
            val methodName = m.ident.name
            val quotedMethodName = "\"" + methodName + "\""
            wr.wl(s"Nan::SetPrototypeMethod(func_template,$quotedMethodName, $methodName);")
          //}
        }
        val cppClassName = cppMarshal.typename(ident, i)
        wr.wl("//Set object prototype")
        wr.wl(s"${cppClassName}_prototype.Reset(objectTemplate);")
        wr.wl("Nan::SetPrototypeMethod(func_template,\"isNull\", isNull);")
      }
      wr.wl
      wr.wl(s"//Add template to target")
      wr.wl(s"target->Set(Nan::New<String>($quotedClassName).ToLocalChecked(), func_template->GetFunction());")
    }

  }

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum): Unit = {}

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record): Unit = {}

  class CppRefs(name: String) {
    val hpp = mutable.TreeSet[String]()
    val hppFwds = mutable.TreeSet[String]()
    val cpp = mutable.TreeSet[String]()

    def find(ty: TypeRef, forwardDeclareOnly: Boolean, nodeMode: Boolean) {
      find(ty.resolved, forwardDeclareOnly, nodeMode)
    }

    def find(m: Meta, forwardDeclareOnly: Boolean, nodeMode: Boolean) = {
      for (r <- marshal.hppReferences(m, name, forwardDeclareOnly, nodeMode)) r match {
        case ImportRef(arg) => hpp.add("#include " + arg)
        case DeclRef(decl, Some(spec.cppNamespace)) => hppFwds.add(decl)
        case DeclRef(_, _) =>
      }
    }

    def find(tm: MExpr, forwardDeclareOnly: Boolean, nodeMode: Boolean) {
      tm.args.foreach((x) => find(x, forwardDeclareOnly, nodeMode))
      find(tm.base, forwardDeclareOnly, nodeMode)
    }
  }
}


