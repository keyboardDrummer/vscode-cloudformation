package cloudformation

import miksilo.editorParser.LazyLogging
import miksilo.languageServer.core.language.Language
import miksilo.languageServer.core.smarts.ConstraintBuilder
import miksilo.languageServer.core.smarts.scopes.objects.ConcreteScope
import miksilo.languageServer.core.smarts.types.objects.PrimitiveType
import miksilo.modularLanguages.core.SolveConstraintsDelta
import miksilo.modularLanguages.core.deltas.{Contract, Delta}
import miksilo.modularLanguages.core.deltas.path.{NodePath, PathRoot}
import miksilo.modularLanguages.deltas.expression.StringLiteralDelta
import miksilo.modularLanguages.deltas.json.{JsonObjectLiteralDelta, JsonStringLiteralDelta}
import miksilo.modularLanguages.deltas.json.JsonObjectLiteralDelta.{MemberKey, MemberShape, ObjectLiteral, ObjectLiteralMember}
import ujson.{Obj, Value}

class CloudFormationTemplate(resourceSpecificationOption: Option[String]) extends Delta with LazyLogging {

  private val resourceTypes = resourceSpecificationOption.fold(Obj.apply())(resourceSpecification => {
    val parsedFile = upickle.default.read[Value](resourceSpecification).obj
    parsedFile("ResourceTypes").asInstanceOf[Obj]
  })

  override def description: String = "Add cloudformation template semantics"

  private val propertyType = PrimitiveType("PropertyKey")
  private val valueType = PrimitiveType("Value")
  override def inject(language: Language): Unit = {
    super.inject(language)

      SolveConstraintsDelta.constraintCollector.add(language, (compilation, builder) => {
      val rootScope = builder.newScope(debugName = "rootScope")

      addResourceTypesFromSchema(resourceTypes, builder, rootScope)

      addPseudoParameters(builder, rootScope)
      val root = compilation.program.asInstanceOf[PathRoot]
      if (root.shape == JsonObjectLiteralDelta.Shape) {
        val program: ObjectLiteral[NodePath] = root

        addParameters(builder, rootScope, program)
        handleResources(builder, rootScope, program)
        resolveRefs(builder, rootScope, program)
      }
    })
  }

  private def handleResources(builder: ConstraintBuilder, rootScope: ConcreteScope, program: ObjectLiteral[NodePath]): Unit = {
    val resources: Option[ObjectLiteral[NodePath]] = program.get("Resources").
      filter(v => v.shape == JsonObjectLiteralDelta.Shape).map(v => ObjectLiteral(v))
    val members = resources.fold(Seq.empty[ObjectLiteralMember[NodePath]])(o => o.members)
    for (resource <- members) {
      builder.declare(resource.key, rootScope, resource.node.getField(MemberKey), Some(valueType))

      if (resource.value.shape == JsonObjectLiteralDelta.Shape) {
        val resourceMembers: ObjectLiteral[NodePath] = resource.value
        val typeOption = resourceMembers.get("Type")
        typeOption match {
          case Some(typeString) =>
            val resourceType = JsonStringLiteralDelta.getValue(typeString)
            val typeDeclaration = builder.resolve(resourceType, rootScope, typeString.getField(JsonStringLiteralDelta.Value))
            val typeScope = builder.getDeclaredScope(typeDeclaration)
            resourceMembers.get("Properties").foreach(_properties => {
              if (_properties.shape == JsonObjectLiteralDelta.Shape) {
                val properties: ObjectLiteral[NodePath] = _properties
                for (property <- properties.members) {
                  if (property.key.nonEmpty)
                    builder.resolveToType(property.key, property.node.getField(MemberKey), typeScope, propertyType)
                }
              }
            })
          case None =>
            // TODO add error for missing type.
        }
      }

    }
  }

  private def resolveRefs(builder: ConstraintBuilder, rootScope: ConcreteScope, program: ObjectLiteral[NodePath]): Unit = {
    program.visitShape(MemberShape, (_member: NodePath) => {
      val member: JsonObjectLiteralDelta.ObjectLiteralMember[NodePath] = _member
      if (member.key == "Ref" && member.value.shape == StringLiteralDelta.Shape) {
        val value = JsonStringLiteralDelta.getValue(member.value)
        val refLocation = member.value.getField(JsonStringLiteralDelta.Value)
        builder.resolveToType(value, refLocation, rootScope, valueType)
      }
    })
  }

  private def addPseudoParameters(builder: ConstraintBuilder, rootScope: ConcreteScope): Unit = {
    val pseudoParameters = Seq("AWS::AccountId", "AWS::NotificationARNs", "AWS::NoValue", "AWS::Partition", "AWS::Region", "AWS::StackId", "AWS::StackName", "AWS::URLSuffix")
    for (pseudoParameter <- pseudoParameters)
      builder.declare(pseudoParameter, rootScope, null, Some(valueType))
  }

  private def addParameters(builder: ConstraintBuilder, rootScope: ConcreteScope,
                            program: ObjectLiteral[NodePath]): Unit = {
    program.getObject("Parameters") match {
      case Some(_parameters) =>
        val parameters: ObjectLiteral[NodePath] = _parameters
        for (parameter <- parameters.members) {
          builder.declare(parameter.key, rootScope, parameter.node.getField(MemberKey), Some(valueType))
        }
      case _ =>
    }
  }

  private def addResourceTypesFromSchema(resourceTypes: Obj, builder: ConstraintBuilder, universe: ConcreteScope): Unit = {
    for (resourceType <- resourceTypes.value) {
      val typeDeclaration = builder.declare(resourceType._1, universe)
      val typeScope = builder.declareScope(typeDeclaration)

      val typeObject = resourceType._2.obj
      val properties = typeObject("Properties").obj
      for (property <- properties) {
        builder.declare(property._1, typeScope, null, Some(propertyType))
      }
    }
  }

  override def dependencies: Set[Contract] = Set.empty
}
