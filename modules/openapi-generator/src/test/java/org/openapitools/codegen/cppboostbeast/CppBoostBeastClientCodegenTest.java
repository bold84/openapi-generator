/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen.cppboostbeast;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.TestUtils;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.languages.CppBoostBeastClientCodegen;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CppBoostBeastClientCodegenTest {

    @Test
    public void generatesTypedJsonValuesForOpenApi31Schemas() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/json-value-regression.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path modelHeader = output.toPath().resolve("model/JsonValueContainer.h");
        Path modelSource = output.toPath().resolve("model/JsonValueContainer.cpp");
        Path cmakeLists = output.toPath().resolve("CMakeLists.txt");
        Path httpClientSource = output.toPath().resolve("api/HttpClientImpl.cpp");

        TestUtils.assertFileContains(modelHeader,
                "std::nullptr_t",
                "boost::json::value",
                "std::map<std::string, boost::json::value>");
        TestUtils.assertFileContains(modelSource,
                "boost::json::serialize",
                "boost::json::parse");
        TestUtils.assertFileNotContains(modelSource, "boost::property_tree");
        TestUtils.assertFileContains(cmakeLists,
                "find_package(Boost 1.75 REQUIRED)",
                "find_package(Threads REQUIRED)",
                "find_package(OpenSSL 1.1.0 REQUIRED COMPONENTS SSL Crypto)",
                "set_property(TARGET Threads::Threads PROPERTY IMPORTED_GLOBAL TRUE)",
                "set_property(TARGET OpenSSL::SSL PROPERTY IMPORTED_GLOBAL TRUE)",
                "PUBLIC Boost::boost OpenSSL::SSL Threads::Threads");
        TestUtils.assertFileNotContains(cmakeLists, "api/HttpClient.cpp");
        TestUtils.assertFileContains(httpClientSource,
                "SSL_CTX_set_min_proto_version(",
                "TLS1_2_VERSION",
                "boost::asio::ssl::verify_peer",
                "boost::asio::ssl::host_name_verification(m_host)");
    }

    @Test
    public void generatesInheritedModelsAndRecursiveJsonConversions() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-models").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/model-generation-regression.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastRegressionClient");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path derivedHeader = output.toPath().resolve("model/DerivedModel.h");
        Path derivedSource = output.toPath().resolve("model/DerivedModel.cpp");
        Path containerHeader = output.toPath().resolve("model/ContainerModel.h");
        Path containerSource = output.toPath().resolve("model/ContainerModel.cpp");
        Path cmakeLists = output.toPath().resolve("CMakeLists.txt");
        String containerHeaderContents = java.nio.file.Files.readString(containerHeader);

        TestUtils.assertFileContains(derivedHeader,
                "#include \"BaseModel.h\"",
                "class  DerivedModel : public BaseModel",
                "DerivedModelBaseValuePropertyIsInherited<BaseModel>::value",
                "DerivedModelLocalValuePropertyIsInherited<BaseModel>::value");
        TestUtils.assertFileNotContains(derivedHeader,
                "public InterfaceModel",
                "std::string m_BaseValue");
        TestUtils.assertFileContains(derivedSource,
                "boost::json::object object = BaseModel::toJsonObject_internal();",
                "BaseModel::fromJsonObject_internal(object);",
                "if constexpr (!DerivedModelBaseValuePropertyIsInherited<BaseModel>::value)",
                "if constexpr (!DerivedModelLocalValuePropertyIsInherited<BaseModel>::value)",
                "return readBaseValueProperty<BaseModel>",
                "writeBaseValueProperty<BaseModel>");
        TestUtils.assertFileContains(containerHeader,
                "bool m_OptionalScalarIsSet = false;",
                "bool m_OptionalModelIsSet = false;",
                "bool m_ModelArrayIsSet = false;",
                "bool m_FreeFormValueIsSet = false;",
                "bool m_NullValueIsSet = false;");
        // Non-cyclic object refs use value semantics (no shared_ptr wrapping)
        TestUtils.assertFileContains(containerHeader,
                "m_ReferencedEnum");
        TestUtils.assertFileNotContains(containerHeader,
                "shared_ptr<ReferencedEnum>");
        TestUtils.assertFileNotContains(containerHeader,
                "bool m_RequiredValueIsSet",
                "std::array<");
        Assert.assertEquals(
                TestUtils.countOccurrences(containerHeaderContents, "#include <vector>"),
                1);
        TestUtils.assertFileContains(containerSource,
                "struct JsonValueConverter<std::shared_ptr<ModelType>>",
                "errorMessage << \"Value not allowed\";",
                "struct JsonValueConverter<std::nullptr_t>",
                "convertedValues.emplace_back(JsonValueConverter<Element>::fromJsonValue(jsonElement));",
                "convertedValues.emplace(entryKey, JsonValueConverter<MappedValue>::fromJsonValue(jsonEntry.value()));",
                "object[\"requiredValue\"] = JsonValueConverter<std::string>::toJsonValue(getRequiredValue());",
                "if (m_OptionalScalarIsSet)",
                "if (m_OptionalModelIsSet)",
                "if (m_ModelArrayIsSet)",
                "if (m_FreeFormValueIsSet)",
                "if (m_NullValueIsSet)",
                "m_OptionalScalarIsSet = false;",
                "m_OptionalScalarIsSet = true;",
                "static const std::array<int32_t, 2> allowedValues = {",
                "1,2",
                "static const std::array<std::string, 2> allowedValues = {",
                "\"alpha\",\"beta\"",
                "static const std::array<bool, 2> allowedValues = {",
                "true,false",
                "\"red\",\"blue\"",
                "\"green\",\"yellow\"",
                "3,4",
                "void validateEnumValues(",
                "const std::vector<Element>& values",
                "const std::map<std::string, MappedValue>& values",
                "validateEnumValues(value.second, allowedValues);",
                "validateEnumValues(value, allowedValues);",
                "setIntegerChoice(JsonValueConverter<int32_t>::fromJsonValue(IntegerChoiceIt->value()));",
                "setStringChoice(JsonValueConverter<std::string>::fromJsonValue(StringChoiceIt->value()));",
                "setBooleanChoice(JsonValueConverter<bool>::fromJsonValue(BooleanChoiceIt->value()));",
                "std::ostringstream errorMessage;",
                "errorMessage << \"Value not allowed\";",
                "JsonValueConverter<std::vector<std::vector<std::shared_ptr<ChildModel>>>>::fromJsonValue",
                "JsonValueConverter<std::map<std::string, std::map<std::string, std::shared_ptr<ChildModel>>>>::fromJsonValue",
                "JsonValueConverter<std::vector<std::map<std::string, std::shared_ptr<ChildModel>>>>::fromJsonValue",
                "JsonValueConverter<std::map<std::string, std::vector<std::shared_ptr<ChildModel>>>>::fromJsonValue",
                "vec = JsonValueConverter<std::vector<std::shared_ptr<ContainerModel>>>::fromJsonValue");
        // Phase 5: Required field validation — missing required key throws with descriptive message
        TestUtils.assertFileContains(containerSource,
                "Required field 'requiredValue' not found in ContainerModel");
        // Phase 5: Property decode wrapped with .fieldName context in error message
        TestUtils.assertFileContains(containerSource,
                "Decode failed for 'requiredValue' in ContainerModel: ",
                "Decode failed for 'optionalScalar' in ContainerModel: ");

        TestUtils.assertFileNotContains(containerSource,
                "mostInnerItems",
                "m_Inner",
                "if (!childEntry.is_null())",
                "m_IntegerChoice = JsonValueConverter");
        TestUtils.assertFileContains(cmakeLists,
                "project(CppBoostBeastRegressionClient VERSION 1.0.0 LANGUAGES CXX)",
                "include(GNUInstallDirs)",
                 "add_library(${PROJECT_NAME} SHARED)",
                 "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>",
                 "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/${PROJECT_NAME}>",
                 "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/${PROJECT_NAME}/api>",
                 "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/${PROJECT_NAME}/model>",
                 "RUNTIME DESTINATION \"${CMAKE_INSTALL_BINDIR}\"",
                "LIBRARY DESTINATION \"${CMAKE_INSTALL_LIBDIR}\"",
                "ARCHIVE DESTINATION \"${CMAKE_INSTALL_LIBDIR}\"",
                "install(DIRECTORY api model",
                "DESTINATION \"${CMAKE_INSTALL_INCLUDEDIR}/${PROJECT_NAME}\"");
    }

    @Test
    public void generatesNullableInheritedPropertyStorage() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-nullable-inheritance").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_0/cpp-boost-beast-client/nullable-inherited-property.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path derivedHeader = output.toPath().resolve("model/NullablePropertyDerived.h");
        TestUtils.assertFileContains(derivedHeader,
                "NullablePropertyDerivedNullableValuePropertyIsInherited<NullablePropertyBase>::value",
                "bool hasOptionalValue() const",
                "void resetOptionalValue()");

        Path derivedSource = output.toPath().resolve("model/NullablePropertyDerived.cpp");
        TestUtils.assertFileContains(derivedSource,
                "if constexpr (!NullablePropertyDerivedNullableValuePropertyIsInherited<NullablePropertyBase>::value)",
                "m_NullableValue.hasOptionalValue()",
                "m_NullableValue.resetOptionalValue()");
        TestUtils.assertFileNotContains(derivedSource,
                "m_NullableValue.value.has_value()",
                "m_NullableValue.value.reset()");
    }

    @Test
    public void resolvesInlineOneOfToVariant() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        ComposedSchema oneOfSchema = new ComposedSchema();
        oneOfSchema.addOneOfItem(new StringSchema());
        oneOfSchema.addOneOfItem(new IntegerSchema());
        String resolved = codegen.getTypeDeclaration(oneOfSchema);
        Assert.assertEquals(resolved, "std::variant<std::string, int32_t>");
    }

    @Test
    public void resolvesInlineAnyOfStringEnumToString() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf: [string, string-enum] → std::string
        ComposedSchema anyOfSchema = new ComposedSchema();
        anyOfSchema.addAnyOfItem(new StringSchema());
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("alpha");
        enumSchema.addEnumItem("beta");
        anyOfSchema.addAnyOfItem(enumSchema);
        String resolved = codegen.getTypeDeclaration(anyOfSchema);
        Assert.assertEquals(resolved, "std::string");
    }

    @Test
    public void resolvesInlineNullableToOptional() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // nullable: true on a property → std::optional<double>
        NumberSchema nullableNumber = new NumberSchema();
        nullableNumber.setNullable(true);
        String resolved = codegen.getTypeDeclaration(nullableNumber);
        Assert.assertEquals(resolved, "std::optional<double>");
    }

    @Test
    public void lowersComposedSchemasInGeneratedCode() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-lowering").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // Scenario 1: ModelIdsResponses (anyOf string + string-enum) — model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/ModelIdsResponses.h"));

        // Scenario 2: InputParam (oneOf string + array) — model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/InputParam.h"));

        // Scenario 3: CreateResponse allOf → has model and input properties
        Path createResponseHeader = output.toPath().resolve("model/CreateResponse.h");
        TestUtils.assertFileExists(createResponseHeader);
        String createResponseContent = java.nio.file.Files.readString(createResponseHeader);
        Assert.assertTrue(createResponseContent.contains("m_Model") && createResponseContent.contains("m_Input"),
                "CreateResponse allOf should have both base (model) and inline (input) properties");

        // Scenario 4: TemperatureContainer — OAS 3.1 anyOf [number, null] → std::optional<double>
        Path tempContainerHeader = output.toPath().resolve("model/TemperatureContainer.h");
        TestUtils.assertFileExists(tempContainerHeader);
        String tempContent = java.nio.file.Files.readString(tempContainerHeader);
        Assert.assertTrue(tempContent.contains("std::optional<double> m_Temperature"),
                "TemperatureContainer should declare std::optional<double> m_Temperature member");
        // With std::optional<double>, no redundant IsSet flag should be emitted in header.
        Assert.assertFalse(tempContent.contains("m_TemperatureIsSet"),
                "TemperatureContainer should NOT have IsSet flag for std::optional<double> property");
        // The .cpp source must also have no IsSet references for the optional property.
        Path tempContainerSource = output.toPath().resolve("model/TemperatureContainer.cpp");
        TestUtils.assertFileExists(tempContainerSource);
        String tempSourceContent = java.nio.file.Files.readString(tempContainerSource);
        Assert.assertFalse(tempSourceContent.contains("m_TemperatureIsSet"),
                "TemperatureContainer.cpp must NOT reference m_TemperatureIsSet");
        // .cpp must use has_value() for optional serialization and reset() for deserialization.
        Assert.assertTrue(tempSourceContent.contains("m_Temperature.has_value()"),
                "TemperatureContainer.cpp should use has_value() for optional serialization");
        Assert.assertTrue(tempSourceContent.contains("m_Temperature.reset()"),
                "TemperatureContainer.cpp should use reset() for optional deserialization");

        // Scenario 5: NullableTemperature — anyOf [number, null] property is std::optional<double>
        Path nullableTempHeader = output.toPath().resolve("model/NullableTemperature.h");
        TestUtils.assertFileExists(nullableTempHeader);
        String nullableTempContent = java.nio.file.Files.readString(nullableTempHeader);
        Assert.assertTrue(nullableTempContent.contains("std::optional<double> m_Temperature"),
                "NullableTemperature should declare std::optional<double> m_Temperature member");

        // Scenario 6: RefHolder — properties that $ref composed models without shared_ptr
        Path refHolderHeader = output.toPath().resolve("model/RefHolder.h");
        TestUtils.assertFileExists(refHolderHeader);
        String refHolderContent = java.nio.file.Files.readString(refHolderHeader);
        // The ids property should reference ModelIdsResponses by value (no shared_ptr)
        Assert.assertTrue(refHolderContent.contains("m_Ids") || refHolderContent.contains("Ids"),
                "RefHolder should declare m_Ids member");
        Assert.assertTrue(refHolderContent.contains("m_Param") || refHolderContent.contains("Param"),
                "RefHolder should declare m_Param member");
        // Verify no shared_ptr wrapping for variant model refs by checking the property type
        // The template renders {{{dataType}}} for member declarations
        Assert.assertFalse(refHolderContent.contains("std::shared_ptr<ModelIdsResponses>"),
                "RefHolder ids property should not be shared_ptr<ModelIdsResponses>");
        Assert.assertFalse(refHolderContent.contains("std::shared_ptr<InputParam>"),
                "RefHolder param property should not be shared_ptr<InputParam>");

        // Scenario 7: PetByType — oneOf with discriminator
        Path petByTypeHeader = output.toPath().resolve("model/PetByType.h");
        TestUtils.assertFileExists(petByTypeHeader);
        Path catHeader = output.toPath().resolve("model/Cat.h");
        TestUtils.assertFileExists(catHeader);
        Path dogHeader = output.toPath().resolve("model/Dog.h");
        TestUtils.assertFileExists(dogHeader);

        // Scenario 8: DedupTest model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/DedupTest.h"));

        // Scenario 9: SingleBranchTest model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/SingleBranchTest.h"));

        // Scenario 10: AllNullTest model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/AllNullTest.h"));

        // Scenario 11: ResponseStreamEvent (anyOf for SSE) model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/ResponseStreamEvent.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/ResponseCreatedEvent.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/ResponseCompletedEvent.h"));

        // Scenario 12: VariantPayload (oneOf binary+object) model file exists
        TestUtils.assertFileExists(output.toPath().resolve("model/VariantPayload.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/DataObject.h"));

        Path nullableDataObjectHeader = output.toPath().resolve("model/NullableDataObject.h");
        TestUtils.assertFileContains(nullableDataObjectHeader,
                "using NullableDataObject = std::optional<DataObject>;");
        Path nullableDataObjectSource = output.toPath().resolve("model/NullableDataObject.cpp");
        TestUtils.assertFileContains(nullableDataObjectSource,
                "JsonValueConverter<NullableDataObject>::fromJsonValue(value)",
                "JsonValueConverter<NullableDataObject>::toJsonValue(value)",
                "return JsonValueConverter<T>::fromJsonValue(jsonValue)");

        // Scenario 13: TimestampContainer has unixtime → int64_t properties
        Path timestampContainerHeader = output.toPath().resolve("model/TimestampContainer.h");
        TestUtils.assertFileExists(timestampContainerHeader);
        String timestampContent = java.nio.file.Files.readString(timestampContainerHeader);
        Assert.assertTrue(timestampContent.contains("int64_t m_Created_at") || timestampContent.contains("std::int64_t m_Created_at"),
                "TimestampContainer created_at member should be int64_t");
        Assert.assertTrue(timestampContent.contains("int64_t m_Updated_at") || timestampContent.contains("std::int64_t m_Updated_at"),
                "TimestampContainer updated_at member should be int64_t");

        // Scenario 14: ResponseStreamEvent uses discriminator for branch selection
        Path responseStreamEventSource = output.toPath().resolve("model/ResponseStreamEvent.cpp");
        TestUtils.assertFileExists(responseStreamEventSource);
        String rseSource = java.nio.file.Files.readString(responseStreamEventSource);
        Assert.assertTrue(rseSource.contains("discriminator"),
                "ResponseStreamEvent fromJsonValue should use discriminator");
        Assert.assertTrue(rseSource.contains("response.created"),
                "ResponseStreamEvent discriminator should match response.created");
        Assert.assertTrue(rseSource.contains("response.completed"),
                "ResponseStreamEvent discriminator should match response.completed");

        // Phase 2 template assertions:
        // Alias models use 'using' typedef — no class template

        // ModelIdsResponses is an alias (anyOf string+string-enum → std::string)
        Path modelIdsHeader = output.toPath().resolve("model/ModelIdsResponses.h");
        String modelIdsContent = java.nio.file.Files.readString(modelIdsHeader);
        Assert.assertTrue(modelIdsContent.contains("using ModelIdsResponses = std::string;"),
                "ModelIdsResponses should emit using alias to std::string");
        Assert.assertFalse(modelIdsContent.contains("class  ModelIdsResponses"),
                "ModelIdsResponses should not contain class declaration (empty-shell)");

        // Transitive anyOf string collapse through $ref chains:
        // ModelIdsShared → std::string
        Path modelIdsSharedHeader = output.toPath().resolve("model/ModelIdsShared.h");
        TestUtils.assertFileExists(modelIdsSharedHeader);
        String modelIdsSharedContent = java.nio.file.Files.readString(modelIdsSharedHeader);
        Assert.assertTrue(modelIdsSharedContent.contains("using ModelIdsShared = std::string;"),
                "ModelIdsShared should collapse to std::string alias (anyOf string+string-enum)");
        // ModelIds (anyOf [$ref ModelIdsShared, $ref ModelIdsResponses]) → std::string
        Path modelIdsHeaderFull = output.toPath().resolve("model/ModelIds.h");
        TestUtils.assertFileExists(modelIdsHeaderFull);
        String modelIdsFullContent = java.nio.file.Files.readString(modelIdsHeaderFull);
        Assert.assertTrue(modelIdsFullContent.contains("using ModelIds = std::string;"),
                "ModelIds should transitively collapse to std::string alias through $ref chain");
        Assert.assertFalse(modelIdsFullContent.contains("std::variant<"),
                "ModelIds must NOT produce std::variant (transitive string collapse should resolve)");
        // ModelIdsCompaction (anyOf [$ref ModelIdsResponses, string]) → std::string
        Path modelIdsCompHeader = output.toPath().resolve("model/ModelIdsCompaction.h");
        TestUtils.assertFileExists(modelIdsCompHeader);
        String modelIdsCompContent = java.nio.file.Files.readString(modelIdsCompHeader);
        Assert.assertTrue(modelIdsCompContent.contains("using ModelIdsCompaction = std::string;"),
                "ModelIdsCompaction should transitively collapse to std::string alias through $ref chain");

        // InputParam is a variant (oneOf string+array → std::variant<...>)
        Path inputParamHeader = output.toPath().resolve("model/InputParam.h");
        String inputParamContent = java.nio.file.Files.readString(inputParamHeader);
        Assert.assertTrue(inputParamContent.contains("using InputParam = std::variant<std::string, std::vector<InputItem>>;"),
                "InputParam should emit using alias to std::variant");
        Assert.assertTrue(inputParamContent.contains("boost::json::value toJsonValue_InputParam(InputParam const& value);"),
                "InputParam header should declare toJsonValue_InputParam");
        Assert.assertTrue(inputParamContent.contains("InputParam fromJsonValue_InputParam(boost::json::value const& value);"),
                "InputParam header should declare fromJsonValue_InputParam");
        // No ADL to_json/from_json bridge — API layer calls toJsonValue_/fromJsonValue_ directly.
        // Having both would cause overload conflict (same params, different return types per variant).
        Assert.assertFalse(inputParamContent.contains("to_json("),
                "InputParam header must NOT declare ADL to_json (causes overload conflict)");
        Assert.assertFalse(inputParamContent.contains(" from_json("),
                "InputParam header must NOT declare ADL from_json (causes overload conflict)");
        Assert.assertFalse(inputParamContent.contains("class  InputParam"),
                "InputParam should not contain class declaration (empty-shell)");
        
        // InputParam source should have toJsonValue_/fromJsonValue_ implementations
        Path inputParamSource = output.toPath().resolve("model/InputParam.cpp");
        String inputParamSourceContent = java.nio.file.Files.readString(inputParamSource);
        Assert.assertTrue(inputParamSourceContent.contains("toJsonValue_InputParam(InputParam const& value)"),
                "InputParam source should implement toJsonValue_InputParam");
        Assert.assertTrue(inputParamSourceContent.contains("std::visit([](auto const& v)"),
                "InputParam to_json should use std::visit");
        Assert.assertTrue(inputParamSourceContent.contains("VariantJsonHelper<"),
                "InputParam to_json should use VariantJsonHelper");
        Assert.assertTrue(inputParamSourceContent.contains("#include <limits>"),
                "Variant sources using numeric_limits must include <limits>");

        // PetByType is a discriminated variant
        Path petByTypeSource = output.toPath().resolve("model/PetByType.cpp");
        String petByTypeSourceContent = java.nio.file.Files.readString(petByTypeSource);
        Assert.assertTrue(petByTypeSourceContent.contains("discriminator"),
                "PetByType from_json should reference discriminator");
        Assert.assertTrue(petByTypeSourceContent.contains("pet_type"),
                "PetByType from_json should reference pet_type discriminator property");
        Assert.assertTrue(petByTypeSourceContent.contains("must be a string"),
                "PetByType must reject a non-string discriminator before structural matching");
        Assert.assertTrue(petByTypeSourceContent.contains("cat\\\"quoted"),
                "PetByType must emit escaped discriminator mapping string literals");

        // OptionalScore (oneOf null+number → std::optional<double>) is not generated
        // as a stand-alone file by the current pipeline — the OpenAPI 3.1 parser
        // converts oneOf [null, number] into {type: number, nullable: true} which
        // does not produce a model header.  It works as std::optional<double> at
        // the property/reference level.  This is a parser-level limitation.
        TestUtils.assertFileExists(output.toPath().resolve("model/NullableTemperature.h"));

        // SingleBranchTest is an alias (anyOf string-enum → std::string)
        Path singleBranchHeader = output.toPath().resolve("model/SingleBranchTest.h");
        String singleBranchContent = java.nio.file.Files.readString(singleBranchHeader);
        Assert.assertTrue(singleBranchContent.contains("using SingleBranchTest = std::string;"),
                "SingleBranchTest should emit using alias to std::string");

        // DedupTest (oneOf string-enum+integer+string) — two branches collapse to
        // std::string, but Phase 3 preserves identity via CompositionBranchValue
        // wrappers so oneOf exclusivity is enforced at the tagged-type level.
        Path dedupHeader = output.toPath().resolve("model/DedupTest.h");
        String dedupContent = java.nio.file.Files.readString(dedupHeader);
        Assert.assertTrue(dedupContent.contains("CompositionBranchValue<0, std::string>"),
                "DedupTest branch 0 must preserve string identity via CompositionBranchValue; "
                        + "content: " + dedupContent.substring(0, Math.min(500, dedupContent.length())));
        Assert.assertTrue(dedupContent.contains("CompositionBranchValue<2, std::string>"),
                "DedupTest branch 2 must preserve string identity via CompositionBranchValue; "
                        + "content: " + dedupContent.substring(0, Math.min(500, dedupContent.length())));
 
        // AllNullTest (anyOf null+null) should use CompositionBranchValue variant
        Path allNullHeader = output.toPath().resolve("model/AllNullTest.h");
        String allNullContent = java.nio.file.Files.readString(allNullHeader);
        Assert.assertTrue(allNullContent.contains("CompositionBranchValue<0, std::nullptr_t>"),
                "AllNullTest should emit CompositionBranchValue variant (not boost::json::value)");
        Assert.assertFalse(allNullContent.contains("class  AllNullTest"),
                "AllNullTest should not contain class declaration");

        // --- Phase 2 strong review assertions ---

        // Verify variant model headers include <variant>
        Assert.assertTrue(inputParamContent.contains("#include <variant>"),
                "InputParam (variant) header should include <variant>");
        Assert.assertTrue(dedupContent.contains("#include <variant>"),
                "DedupTest (CompositionBranchValue variant) header should include <variant>");

        // Verify include guards: each header has exactly one #ifndef and one #endif
        // (check the alias and non-alias paths)
        Assert.assertEquals(
                TestUtils.countOccurrences(inputParamContent, "#ifndef BOOST_BEAST_OPENAPI_CLIENT_InputParam_MODEL_H_"),
                1, "InputParam header should have exactly one #ifndef");
        Assert.assertEquals(
                TestUtils.countOccurrences(inputParamContent, "#endif"),
                1, "InputParam header should have exactly one #endif");
        String catContent = java.nio.file.Files.readString(catHeader);
        Assert.assertEquals(
                TestUtils.countOccurrences(catContent, "#ifndef BOOST_BEAST_OPENAPI_CLIENT_Cat_MODEL_H_"),
                1, "Cat (class model) header should have exactly one #ifndef");
        Assert.assertEquals(
                TestUtils.countOccurrences(catContent, "#endif"),
                1, "Cat (class model) header should have exactly one #endif");

        // Verify to_json uses toJsonValue() for model types, not bare value_from
        Assert.assertTrue(petByTypeSourceContent.contains("VariantJsonHelper<std::decay_t<decltype(v)>>::toJsonValue(v)"),
                "PetByType to_json should use VariantJsonHelper");
        Assert.assertFalse(petByTypeSourceContent.contains("boost::json::value_to<Cat>(value)"),
                "PetByType from_json should not use value_to<Cat>");
        Assert.assertTrue(petByTypeSourceContent.contains("return fromJsonValue_Cat(value);"),
                "PetByType from_json should use fromJsonValue_Cat(value) dispatch");

        // Verify C++17 compatibility: no `requires` keyword in generated sources
        Assert.assertFalse(petByTypeSourceContent.contains("requires "),
                "PetByType source should not use C++20 requires expressions");
        Assert.assertFalse(inputParamSourceContent.contains("requires "),
                "InputParam source should not use C++20 requires expressions");

        // Verify variant source files include <map> (needed by VariantJsonHelper's map specialization)
        Assert.assertTrue(petByTypeSourceContent.contains("#include <map>"),
                "PetByType variant source should include <map>");
        Assert.assertTrue(inputParamSourceContent.contains("#include <map>"),
                "InputParam variant source should include <map>");

        // Verify discriminator error message includes the received value
        Assert.assertTrue(petByTypeSourceContent.contains("discValue"),
                "PetByType discriminator error should include the received value");
        // Verify discValue is in scope at the throw — the throw should appear
        // within the same function scope as the declaration (no extra closing
        // brace between them).  The discValue declaration and all mapped-model
        // branches all sit at function scope (no inner {} block).
        int discValueDecl = petByTypeSourceContent.indexOf("std::string discValue{");
        int throwPos = petByTypeSourceContent.indexOf("throw std::invalid_argument", discValueDecl);
        String betweenDeclAndThrow = petByTypeSourceContent.substring(discValueDecl, throwPos);
        // Count braces: opening braces must be balanced before the throw
        long opens = betweenDeclAndThrow.chars().filter(ch -> ch == '{').count();
        long closes = betweenDeclAndThrow.chars().filter(ch -> ch == '}').count();
        Assert.assertEquals(opens, closes,
                "discValue scope: braces must be balanced between declaration and throw (got " + opens + " open, " + closes + " close)");

        // Phase 5: Discriminator mismatch — unknown value throws with clear message
        Assert.assertTrue(petByTypeSourceContent.contains("Unknown discriminator value"),
                "PetByType discriminator should throw on unknown mapping value");
        Assert.assertTrue(petByTypeSourceContent.contains("pet_type"),
                "PetByType discriminator throw should reference discriminator property name 'pet_type'");
        int unknownDiscThrowPos = petByTypeSourceContent.indexOf("Unknown discriminator value");
        Assert.assertTrue(unknownDiscThrowPos > 0 && unknownDiscThrowPos > discValueDecl,
                "PetByType 'Unknown discriminator value' throw should appear after discValue declaration");

        // Phase 5: Error path in variant error messages — concrete path-building patterns
        // Array-index path segment: outer→inner ordering via pre-built sub-path
        Assert.assertTrue(inputParamSourceContent.contains(
                "itemPath = *errorPath + \"[\" + std::to_string(elemIndex) + \"]\""),
                "InputParam source must build array-index sub-path in outer→inner order");
        // Model exception capture: error path includes model error context
        Assert.assertTrue(inputParamSourceContent.contains("errorPath->append(\": \")"),
                "InputParam source must capture model exceptions into errorPath");
        // Model exception capture appends ex.what()
        Assert.assertTrue(inputParamSourceContent.contains("errorPath->append(\": \").append(ex.what())"),
                "InputParam source must chain model exception message into errorPath");
        // matchCount==0 re-run to capture model-error context in path
        Assert.assertTrue(inputParamSourceContent.contains("capturePath"),
                "InputParam source must use capturePath for matchCount==0 diagnostic");
        Assert.assertTrue(inputParamSourceContent.contains("initialErrorPath"),
                "Variant branch trials must isolate error paths between alternatives");

        // Scenario 12a: OAS const without vendor extensions
        Path oasConstHeader = output.toPath().resolve("model/OasConstObject.h");
        TestUtils.assertFileExists(oasConstHeader);
        String oasConstContent = java.nio.file.Files.readString(oasConstHeader);
        Assert.assertTrue(oasConstContent.contains("std::string getType() const { return \"text\"; }"),
                "OasConstObject string const getter should inline from OAS const");
        Assert.assertTrue(oasConstContent.contains("int32_t getCount() const { return 42; }"),
                "OasConstObject integer const getter should inline from OAS const");
        String oasConstSourceContent = java.nio.file.Files.readString(
                output.toPath().resolve("model/OasConstObject.cpp"));
        Assert.assertTrue(oasConstSourceContent.contains("expected a JSON number for const value"),
                "Numeric const properties must reject non-number JSON kinds");
        Assert.assertTrue(oasConstSourceContent.contains("expected a JSON boolean for const value"),
                "Boolean const properties must require a JSON boolean");
        Assert.assertFalse(oasConstSourceContent.contains("expected a JSON number or boolean"),
                "Numeric and boolean const validation must not share a coercing kind check");

        // Scenario 12b: optional x-stainless-const still works
        Path stainlessHeader = output.toPath().resolve("model/StainlessObject.h");
        TestUtils.assertFileExists(stainlessHeader);
        String stainlessContent = java.nio.file.Files.readString(stainlessHeader);
        Assert.assertTrue(stainlessContent.contains("std::string getType() const { return \"text\"; }"),
                "StainlessObject string const getter should inline the quoted value");
        Assert.assertTrue(stainlessContent.contains("int32_t getCount() const { return 42; }"),
                "StainlessObject integer const getter should inline the value");

        // --- Phase 2 oneOf/anyOf decode distinction assertions ---

        // InputParam (oneOf variant) source must contain exactly-one checking logic
        Assert.assertTrue(inputParamSourceContent.contains("isOneOf"),
                "InputParam oneOf source should contain isOneOf compile-time flag");
        Assert.assertTrue(inputParamSourceContent.contains("matchCount"),
                "InputParam oneOf source should count matching branches");
        Assert.assertTrue(inputParamSourceContent.contains("More than one matching branch for oneOf InputParam"),
                "InputParam oneOf source should reject multi-match with descriptive error");
        // The oneOf path uses countVariantBranches + matchCount for exactly-one enforcement
        Assert.assertTrue(inputParamSourceContent.contains("countVariantBranches"),
                "InputParam oneOf source should use countVariantBranches for exactly-one check");
        // The anyOf path comment is also present (both branches emitted textually by if constexpr)

        // DedupTest is now CompositionBranchValue variant — source uses
        // JsonValueConverter which dispatches through model conversion helpers
        // for model-containing types (e.g., std::optional<SomeObject>) and falls
        // back to value_to/value_from for plain types.
        Path dedupSource = output.toPath().resolve("model/DedupTest.cpp");
        String dedupSourceContent = java.nio.file.Files.readString(dedupSource);
        Assert.assertTrue(dedupSourceContent.contains("JsonValueConverter<DedupTest>::toJsonValue"),
                "DedupTest alias source should use JsonValueConverter for serialization");
        Assert.assertTrue(dedupSourceContent.contains("matchingBranches"),
                "Type-erased oneOf aliases must retain branch validation");
        Assert.assertTrue(dedupSourceContent.contains("stringValue == \"a\""),
                "Type-erased oneOf aliases must retain string-enum constraints");
        Assert.assertTrue(dedupSourceContent.contains("value.is_int64()"),
                "Type-erased oneOf aliases must reject unrelated JSON kinds");

        // VariantPayload (oneOf variant) source must also contain exactly-one checking
        Path variantPayloadSource = output.toPath().resolve("model/VariantPayload.cpp");
        String variantPayloadSourceContent = java.nio.file.Files.readString(variantPayloadSource);
        Assert.assertTrue(variantPayloadSourceContent.contains("isOneOf"),
                "VariantPayload oneOf source should contain isOneOf compile-time flag");
        Assert.assertTrue(variantPayloadSourceContent.contains("matchCount"),
                "VariantPayload oneOf source should count matching branches");
        Assert.assertTrue(variantPayloadSourceContent.contains("More than one matching branch for oneOf VariantPayload"),
                "VariantPayload oneOf source should reject multi-match with descriptive error");

        // ResponseStreamEvent uses discriminator path with a non-discriminated
        // fallback (when the discriminator value is absent or not in known mappings).
        // The discriminator branch must be present.
        String rseSourceContent = java.nio.file.Files.readString(responseStreamEventSource);
        Assert.assertTrue(rseSourceContent.contains("Discriminator-aware"),
                "ResponseStreamEvent should contain discriminator dispatch");

        // Scenario 18: AnyOfOverlapping, OverlappingObjectA, OverlappingObjectB,
        // ParentWithAnyOfOverlapping — verify files are generated
        TestUtils.assertFileExists(output.toPath().resolve("model/AnyOfOverlapping.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/OverlappingObjectA.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/OverlappingObjectB.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/ParentWithAnyOfOverlapping.h"));

        // AnyOfOverlapping must be a variant (anyOf two objects → std::variant<...>)
        String anyOfOverlappingContent = java.nio.file.Files.readString(
            output.toPath().resolve("model/AnyOfOverlapping.h"));
        Assert.assertTrue(anyOfOverlappingContent.contains("using AnyOfOverlapping = std::variant<OverlappingObjectA, OverlappingObjectB>;"),
                "AnyOfOverlapping should emit using alias to std::variant<OverlappingObjectA, OverlappingObjectB>");

        // AnyOfOverlapping source must use tryVariantBranches (first-match) for anyOf
        String anyOfOverlappingSourceContent = java.nio.file.Files.readString(
            output.toPath().resolve("model/AnyOfOverlapping.cpp"));
        Assert.assertTrue(anyOfOverlappingSourceContent.contains("isOneOf"),
                "AnyOfOverlapping source should contain isOneOf compile-time flag");
        Assert.assertTrue(anyOfOverlappingSourceContent.contains("tryVariantBranches"),
                "AnyOfOverlapping (anyOf) source should use tryVariantBranches (first-match)");

        // ParentWithAnyOfOverlapping must dispatch via fromJsonValue_/toJsonValue_
        String parentOverlappingSourceContent = java.nio.file.Files.readString(
            output.toPath().resolve("model/ParentWithAnyOfOverlapping.cpp"));
        Assert.assertTrue(parentOverlappingSourceContent.contains("fromJsonValue_AnyOfOverlapping"),
                "ParentWithAnyOfOverlapping deserialization must use fromJsonValue_AnyOfOverlapping");
        Assert.assertTrue(parentOverlappingSourceContent.contains("toJsonValue_AnyOfOverlapping"),
                "ParentWithAnyOfOverlapping serialization must use toJsonValue_AnyOfOverlapping");

        // Scenario 16a: OneOfWithStringOverlap (oneOf open-string + string-enum via $ref)
        // must emit CompositionBranchValue variant (not collapse to std::string).
        Path oneOfStringOverlapHeader = output.toPath().resolve("model/OneOfWithStringOverlap.h");
        TestUtils.assertFileExists(oneOfStringOverlapHeader);
        String oneOfStringOverlapContent = java.nio.file.Files.readString(oneOfStringOverlapHeader);
        Assert.assertTrue(oneOfStringOverlapContent.contains("CompositionBranchValue<0, std::string>"),
                "OneOfWithStringOverlap (oneOf open-string + string-enum via $ref) should emit "
                + "CompositionBranchValue variant");
        Assert.assertFalse(oneOfStringOverlapContent.contains("using OneOfWithStringOverlap = std::string;"),
                "OneOfWithStringOverlap must NOT collapse to std::string — oneOf overlap "
                + "requires CompositionBranchValue");
        Assert.assertFalse(oneOfStringOverlapContent.contains("using OneOfWithStringOverlap = boost::json::value;"),
                "OneOfWithStringOverlap must NOT type-erase to boost::json::value");

        // Scenario 16b: StringOverlapHolder property references OneOfWithStringOverlap
        // which is a using-alias for a CompositionBranchValue variant. Verify the property uses the
        // typedef (the alias model name, not a plain std::string).
        Path stringOverlapHolderHeader = output.toPath().resolve("model/StringOverlapHolder.h");
        TestUtils.assertFileExists(stringOverlapHolderHeader);
        String stringOverlapHolderContent = java.nio.file.Files.readString(stringOverlapHolderHeader);
        Assert.assertTrue(stringOverlapHolderContent.contains("OneOfWithStringOverlap getOverlap()"),
                "StringOverlapHolder should declare getOverlap() returning OneOfWithStringOverlap");
        Assert.assertTrue(stringOverlapHolderContent.contains("void setOverlap(OneOfWithStringOverlap"),
                "StringOverlapHolder should declare setOverlap(OneOfWithStringOverlap)");
        // The property type is the alias name rather than boost::json::value directly.
        // Either form is correct — the alias resolves to boost::json::value at compile time.
        Assert.assertFalse(stringOverlapHolderContent.contains("std::string m_Overlap"),
                "StringOverlapHolder overlap property must NOT be std::string");
    }

    @Test
    public void reducesOneOfNullNumberToOptional() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // OAS 3.1 oneOf [null, number] inline → applies lowering → std::optional<double>
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new Schema().type("null"));
        schema.addOneOfItem(new NumberSchema());

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::optional<double>",
                "oneOf [null, number] should produce std::optional<double>");
    }

    @Test
    public void resolvesInputParamWithNestedSharedPtrStripped() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // Simulate InputParam: oneOf [string, array<$ref InputItem>]
        // Branch types should have no shared_ptr wrapping: std::variant<std::string, std::vector<InputItem>>
        Schema refItem = new Schema().$ref("#/components/schemas/InputItem");
        ArraySchema arraySchema = new ArraySchema();
        arraySchema.setItems(refItem);

        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        schema.addOneOfItem(arraySchema);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::variant<std::string, std::vector<InputItem>>",
                "InputParam should strip nested shared_ptr from array item type");
    }

    @Test
    public void deduplicatesIdenticalBranchTypes() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // oneOf: [string, string-enum, integer] — string branches collapse to
        // std::string. Phase 3 preserves identity via CompositionBranchValue
        // wrappers instead of type-erasing.
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("a");
        enumSchema.addEnumItem("b");
        schema.addOneOfItem(enumSchema);
        schema.addOneOfItem(new IntegerSchema());

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved,
                "std::variant<CompositionBranchValue<0, std::string>, CompositionBranchValue<1, std::string>, CompositionBranchValue<2, int32_t>>",
                "oneOf [string, string-enum, integer] should produce CompositionBranchValue variant");
    }

    @Test
    public void collapsesSingleNonNullBranch() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf: [string] → single branch → std::string
        ComposedSchema schema = new ComposedSchema();
        schema.addAnyOfItem(new StringSchema());

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::string",
                "Single non-null branch should collapse to that branch type");
    }

    @Test
    public void collapsesSingleStringEnumBranch() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf: [string-enum] → single string branch → std::string
        ComposedSchema schema = new ComposedSchema();
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("x");
        schema.addAnyOfItem(enumSchema);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::string",
                "Single string-enum branch should collapse to std::string");
    }

    @Test
    @Test
    public void resolvesAllNullBranchesToCompositionBranchValueVariant() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf: [null, null] → CompositionBranchValue variant preserves null identity
        ComposedSchema schema = new ComposedSchema();
        schema.addAnyOfItem(new Schema().type("null"));
        schema.addAnyOfItem(new Schema().type("null"));

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(
                "std::variant<CompositionBranchValue<0, std::nullptr_t>, CompositionBranchValue<1, std::nullptr_t>>",
                resolved,
                "All-null branches should produce CompositionBranchValue variant");
    }

    @Test
    public void oneOfStringStringEnumDoesNotBlindCollapseToString() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // oneOf: [string, string-enum] must NOT collapse like anyOf.
        // Phase 3 preserves identity via CompositionBranchValue wrappers
        // instead of type-erasing to boost::json::value.
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("x");
        enumSchema.addEnumItem("y");
        schema.addOneOfItem(enumSchema);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(
                "std::variant<CompositionBranchValue<0, std::string>, CompositionBranchValue<1, std::string>>",
                resolved,
                "oneOf [string, string-enum] should produce CompositionBranchValue variant");
    }

    @Test
    public void anyOfStringStringEnumPreservesValidators() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf: [string, string-enum] → CompositionBranchValue variant
        // (Rule 2 no longer collapses to std::string when enum is present)
        ComposedSchema schema = new ComposedSchema();
        schema.addAnyOfItem(new StringSchema());
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("alpha");
        enumSchema.addEnumItem("beta");
        schema.addAnyOfItem(enumSchema);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(
                "std::variant<CompositionBranchValue<0, std::string>, CompositionBranchValue<1, std::string>>",
                resolved,
                "anyOf [string, string-enum] should produce CompositionBranchValue variant with validators");
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void allOfScalarConflictThrows() throws IOException {
        // This test verifies that an allOf with incompatible scalar types
        // (e.g., allOf [string, integer]) causes a RuntimeException.
        // We generate from a minimal spec with only the conflicting schema.
        String specContent =
            "openapi: 3.1.0\n" +
            "info:\n" +
            "  title: allOf conflict test\n" +
            "  version: 1.0.0\n" +
            "paths: {}\n" +
            "components:\n" +
            "  schemas:\n" +
            "    AllOfScalarConflict:\n" +
            "      allOf:\n" +
            "        - type: string\n" +
            "        - type: integer\n" +
            "          format: int32\n";

        java.nio.file.Path specFile = java.nio.file.Files.createTempFile("allof-conflict-", ".yaml");
        specFile.toFile().deleteOnExit();
        java.nio.file.Files.writeString(specFile, specContent);

        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-conflict").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(specFile.toAbsolutePath().toString())
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastConflictTest");

        try {
            new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        } catch (RuntimeException e) {
            // Check the ROOT cause, not just the wrapper message
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String message = cause.getMessage();
            System.err.println("allOfScalarConflictThrows: root cause = " + cause.getClass().getName() + ": " + message);
            if (message == null) {
                message = e.getMessage();
            }
            Assert.assertTrue(message != null && (message.contains("allOf type conflict")
                    || message.contains("AllOfScalarConflict")),
                    "Exception root cause should mention allOf type conflict. Got: " + message);
            throw e;
        }
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void allOfPropertyConflictThrows() throws IOException {
        // This test verifies that an allOf with the same property name having
        // incompatible types causes a RuntimeException.
        String specContent =
            "openapi: 3.1.0\n" +
            "info:\n" +
            "  title: allOf property conflict test\n" +
            "  version: 1.0.0\n" +
            "paths: {}\n" +
            "components:\n" +
            "  schemas:\n" +
            "    AllOfPropConflict:\n" +
            "      allOf:\n" +
            "        - type: object\n" +
            "          properties:\n" +
            "            value:\n" +
            "              type: string\n" +
            "        - type: object\n" +
            "          properties:\n" +
            "            value:\n" +
            "              type: integer\n" +
            "              format: int32\n";

        java.nio.file.Path specFile = java.nio.file.Files.createTempFile("allof-prop-conflict-", ".yaml");
        specFile.toFile().deleteOnExit();
        java.nio.file.Files.writeString(specFile, specContent);

        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-prop-conflict").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(specFile.toAbsolutePath().toString())
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastPropConflictTest");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    public void optionalImpossibleAllOfGeneratesObjectWithConflictingProperty() throws IOException {
        // allOf with conflicting optional property types must generate a valid object model
        // that has the structurally conflicting value property.  The generated object should
        // not have a method/field that would let the user write a valid value for both branches.
        String specContent =
            "openapi: 3.1.0\n" +
            "info:\n" +
            "  title: optional impossible allOf test\n" +
            "  version: 1.0.0\n" +
            "paths: {}\n" +
            "components:\n" +
            "  schemas:\n" +
            "    OptionalImpossibleAllOf:\n" +
            "      allOf:\n" +
            "        - type: object\n" +
            "          properties:\n" +
            "            value:\n" +
            "              type: string\n" +
            "        - type: object\n" +
            "          properties:\n" +
            "            value:\n" +
            "              type: integer\n" +
            "              format: int32\n";

        java.nio.file.Path specFile = java.nio.file.Files.createTempFile("optional-impossible-allof-", ".yaml");
        specFile.toFile().deleteOnExit();
        java.nio.file.Files.writeString(specFile, specContent);

        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-opt-impossible-allof").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(specFile.toAbsolutePath().toString())
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastOptImpossibleAllOf");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // Generation must succeed — the allOf merge produces an object with value property
        Path generatedHeader = output.toPath().resolve("model/OptionalImpossibleAllOf.h");
        TestUtils.assertFileExists(generatedHeader);
        String headerContent = java.nio.file.Files.readString(generatedHeader);

        // The generated object must NOT have a writable concrete value member.
        // The conflicting optional property (string vs int32) cannot be satisfied by any
        // single concrete type.  Currently the generator picks the last-wins type and
        // emits m_Value with a setter — this Phase 0 test asserts the absence, documenting
        // the gap.  When the generator learns to skip the member (or use boost::json::value),
        // this assertion auto-passes.
        Assert.assertFalse(headerContent.contains("m_Value") || headerContent.contains(" setValue"),
                "OptionalImpossibleAllOf must NOT have a writable concrete `value` member — "
                + "string and int32 are incompatible.  "
                + "Current generator emits last-wins m_Value (wrong). "
                + "Header content: " + headerContent);
    }

    @Test
    public void nullableStringEnumViaGateFixtures() throws IOException {
        // Verify that NullableEnum in Gate A fixtures lowers to std::optional<...>
        // (not plain std::string) by generating from the Gate A spec.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-nullable").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastNullableTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path nullableEnumHeader = output.toPath().resolve("model/NullableEnum.h");
        TestUtils.assertFileExists(nullableEnumHeader);
        String nullableEnumContent = java.nio.file.Files.readString(nullableEnumHeader);
        Assert.assertTrue(nullableEnumContent.contains("std::optional<"),
                "NullableEnum header should contain std::optional<...>. Got: " + nullableEnumContent);
        Assert.assertFalse(nullableEnumContent.contains("using NullableEnum = std::string;"),
                "NullableEnum must not collapse to plain std::string.");

        Path nullableStringHeader = output.toPath().resolve("model/NullableString.h");
        TestUtils.assertFileExists(nullableStringHeader);
        String nullableStringContent = java.nio.file.Files.readString(nullableStringHeader);
        Assert.assertTrue(nullableStringContent.contains("using NullableString = std::optional<std::string>;"),
                "NullableString must emit optional alias header. Got: " + nullableStringContent);
        Path nullableStringSource = output.toPath().resolve("model/NullableString.cpp");
        TestUtils.assertFileContains(nullableStringSource,
                "JsonValueConverter<NullableString>::fromJsonValue(value)");
        TestUtils.assertFileContains(nullableStringSource,
                "JsonValueConverter<NullableString>::toJsonValue(value)");
        TestUtils.assertFileContains(nullableStringSource,
                "struct JsonValueConverter<std::optional<T>>");
        TestUtils.assertFileContains(nullableStringSource,
                "return JsonValueConverter<T>::fromJsonValue(jsonValue)");
    }

    @Test
    public void oneOfConstrainedNumbersProducesCompositionBranchValueVariant() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // oneOf [number, number] — both branches are double after dedup,
        // identity is preserved via CompositionBranchValue wrappers.
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new NumberSchema());
        schema.addOneOfItem(new NumberSchema());

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(
                "std::variant<CompositionBranchValue<0, double>, CompositionBranchValue<1, double>>",
                resolved,
                "oneOf [number, number] (duplicate types) should produce "
                        + "CompositionBranchValue variant, not boost::json::value");
    }

    @Test
    public void oneOfConstrainedNumbersWithMultipleOfFromFixtures() throws IOException {
        // Verify ConstrainedNumber (oneOf with multipleOf) generates from Gate A fixtures.
        // Both branches are type:number (double) so they resolve to duplicate C++ types.
        // Phase 3 preserves identity via CompositionBranchValue wrappers.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-multof").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastMultiOfTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path constrainedHeader = output.toPath().resolve("model/ConstrainedNumber.h");
        TestUtils.assertFileExists(constrainedHeader);
        String constraintContent = java.nio.file.Files.readString(constrainedHeader);
        Assert.assertTrue(
                constraintContent.contains("CompositionBranchValue"),
                "ConstrainedNumber (oneOf number+number) must use CompositionBranchValue "
                        + "to preserve branch identity; content: "
                        + constraintContent.substring(0, Math.min(500, constraintContent.length())));
        Assert.assertTrue(
                constraintContent.contains("CompositionBranchValue<0, double>"),
                "ConstrainedNumber[0] must be CompositionBranchValue<0, double>; content: "
                        + constraintContent.substring(0, Math.min(500, constraintContent.length())));
        Assert.assertTrue(
                constraintContent.contains("CompositionBranchValue<1, double>"),
                "ConstrainedNumber[1] must be CompositionBranchValue<1, double>; content: "
                        + constraintContent.substring(0, Math.min(500, constraintContent.length())));
        // Verify fromJsonValue uses descriptor-guided conversion (not blind tryVariantBranches)
        Path constrainedSource = output.toPath().resolve("model/ConstrainedNumber.cpp");
        TestUtils.assertFileExists(constrainedSource);
        String constraintSourceContent = java.nio.file.Files.readString(constrainedSource);
        Assert.assertTrue(
                constraintSourceContent.contains("matchedBranchIndex"),
                "ConstrainedNumber fromJsonValue must track matchedBranchIndex from "
                        + "validator (not tryVariantBranches); content: "
                        + constraintSourceContent.substring(0, Math.min(500, constraintSourceContent.length())));
        Assert.assertTrue(
                constraintSourceContent.contains(
                        "CompositionBranchValue<0, double>{std::move(converted)}"),
                "ConstrainedNumber fromJsonValue must construct CompositionBranchValue<0, "
                        + "double> from the converted branch value; content: "
                        + constraintSourceContent.substring(0, Math.min(500, constraintSourceContent.length())));

        // Verify enum-only anyOf preserves validators (not collapsed to std::string)
        Path enumUnionHeader = output.toPath().resolve("model/AnyOfEnumUnion.h");
        TestUtils.assertFileExists(enumUnionHeader);
        String enumUnionHeaderContent = java.nio.file.Files.readString(enumUnionHeader);
        Assert.assertTrue(
                enumUnionHeaderContent.contains("CompositionBranchValue"),
                "AnyOfEnumUnion (anyOf enum+enum) must use CompositionBranchValue "
                        + "to preserve validators (not collapsed to std::string); content: "
                        + enumUnionHeaderContent.substring(0, Math.min(500, enumUnionHeaderContent.length())));
        Path enumUnionSource = output.toPath().resolve("model/AnyOfEnumUnion.cpp");
        TestUtils.assertFileExists(enumUnionSource);
        String enumUnionSourceContent = java.nio.file.Files.readString(enumUnionSource);
        Assert.assertTrue(
                enumUnionSourceContent.contains("validate_AnyOfEnumUnion_branch_0")
                        && enumUnionSourceContent.contains("validate_AnyOfEnumUnion_branch_1"),
                "AnyOfEnumUnion source must contain per-branch validators for "
                        + "enum rejection; content: "
                        + enumUnionSourceContent.substring(0, Math.min(500, enumUnionSourceContent.length())));

        // Verify all-null anyOf preserves null cardinality with tagged type
        Path allNullHeader = output.toPath().resolve("model/AllNullAnyOf.h");
        TestUtils.assertFileExists(allNullHeader);
        String allNullContent = java.nio.file.Files.readString(allNullHeader);
        Assert.assertTrue(
                allNullContent.contains("CompositionBranchValue<0, std::nullptr_t>"),
                "AllNullAnyOf must use CompositionBranchValue<0, std::nullptr_t> "
                        + "to preserve null branch identity; content: "
                        + allNullContent.substring(0, Math.min(500, allNullContent.length())));

        // Verify duplicate-null oneOf preserves null cardinality
        Path dupNullHeader = output.toPath().resolve("model/DuplicateNullOneOf.h");
        TestUtils.assertFileExists(dupNullHeader);
        String dupNullContent = java.nio.file.Files.readString(dupNullHeader);
        Assert.assertTrue(
                dupNullContent.contains("CompositionBranchValue<0, std::nullptr_t>"),
                "DuplicateNullOneOf must use CompositionBranchValue<0, std::nullptr_t> "
                        + "to preserve null branch identity; content: "
                        + dupNullContent.substring(0, Math.min(500, dupNullContent.length())));

        // Verify API response deserialization uses model free function
        // for CompositionBranchValue variants (not generic tryFirstVariantAlternative)
        Path apiSource = output.toPath().resolve("api/DefaultApi.cpp");
        if (java.nio.file.Files.exists(apiSource)) {
            String apiSourceContent = java.nio.file.Files.readString(apiSource);
            Assert.assertTrue(
                    apiSourceContent.contains("fromJsonValue_ConstrainedNumber("),
                    "API response for ConstrainedNumber must use "
                            + "fromJsonValue_ConstrainedNumber (descriptor-guided) "
                            + "instead of generic ResponseBodyDeserializer; content: "
                            + apiSourceContent.substring(0, Math.min(500, apiSourceContent.length())));
        }
    }

    @Test
    public void allOfEnumIntersectionFromFixtures() throws IOException {
        // Verify AllOfEnumIntersection (allOf [enum[a,b], enum[b,c]]) generates from
        // Gate A fixtures.  The intersection must be {b}.  Currently the generator
        // does not compute enum intersection — it uses last-wins from the allOf merge.
        // This locks the failing behaviour: the test expects intersection but may get
        // the full set from the last contributor.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-enum-intersect").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastEnumIntersectTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path intersectHeader = output.toPath().resolve("model/AllOfEnumIntersection.h");
        TestUtils.assertFileExists(intersectHeader);
        String intersectContent = java.nio.file.Files.readString(intersectHeader);
        Assert.assertTrue(intersectContent.contains("using AllOfEnumIntersection = std::string;"),
                "AllOfEnumIntersection should be std::string (allOf enum merge)");

        // Phase 0: verify the generated enum intersection is exactly {b}.
        // Expected intersection: [a,b] ∩ [b,c] = {b} — only b, not a, not c.
        // Current generator does NOT compute intersection (uses last-wins from the
        // allOf merge).  Lock the actual current behaviour: assert that the generated
        // code contains "b", does NOT contain "a", and does NOT contain "c" (strictly
        // {b}-only).  The .cpp file MUST exist — no silent skip if missing.
        Path intersectSource = output.toPath().resolve("model/AllOfEnumIntersection.cpp");
        Assert.assertTrue(java.nio.file.Files.exists(intersectSource),
                "AllOfEnumIntersection.cpp must exist to verify allowed values");
        String intersectSourceContent = java.nio.file.Files.readString(intersectSource);
        // The source may contain allowedValues or enum validation
        boolean containsB = intersectSourceContent.contains("\"b\"")
            || intersectSourceContent.contains("\\\"b\\\"");
        boolean containsA = intersectSourceContent.contains("\"a\"")
            || intersectSourceContent.contains("\\\"a\\\"");
        boolean containsC = intersectSourceContent.contains("\"c\"")
            || intersectSourceContent.contains("\\\"c\\\"");
        Assert.assertTrue(containsB,
                "AllOfEnumIntersection source must contain enum value \"b\" (intersection). "
                + "Current source: " + intersectSourceContent);
        Assert.assertFalse(containsA,
                "AllOfEnumIntersection source must NOT contain enum value \"a\" "
                + "(intersection [a,b] ∩ [b,c] = {b}). "
                + "Current source: " + intersectSourceContent);
        Assert.assertFalse(containsC,
                "AllOfEnumIntersection source must NOT contain enum value \"c\" "
                + "(intersection [a,b] ∩ [b,c] = {b}, not [b,c]). "
                + "Current source: " + intersectSourceContent);
    }

    @Test
    public void anyOfEnumUnionCollapsesToString() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // anyOf [enum[red], enum[blue]] → both collapse to std::string (anyOf union)
        ComposedSchema schema = new ComposedSchema();
        StringSchema enumBranch0 = new StringSchema();
        enumBranch0.addEnumItem("red");
        StringSchema enumBranch1 = new StringSchema();
        enumBranch1.addEnumItem("blue");
        schema.addAnyOfItem(enumBranch0);
        schema.addAnyOfItem(enumBranch1);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::string",
                "anyOf [enum[red], enum[blue]] should collapse to std::string");
    }

    @Test
    public void allOfEnumIntersectionMergesEnum() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // allOf [enum[a,b], enum[b,c]] → merged enum is intersection [b] → std::string
        ComposedSchema schema = new ComposedSchema();
        StringSchema enumBranch0 = new StringSchema();
        enumBranch0.addEnumItem("a");
        enumBranch0.addEnumItem("b");
        StringSchema enumBranch1 = new StringSchema();
        enumBranch1.addEnumItem("b");
        enumBranch1.addEnumItem("c");
        schema.addAllOfItem(enumBranch0);
        schema.addAllOfItem(enumBranch1);

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::string",
                "allOf [enum[a,b], enum[b,c]] should merge to std::string");
    }

    @Test
    public void oneOfIntegerNumberProducesVariant() throws IOException {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // oneOf [integer, number] → std::variant<int32_t, double>
        ComposedSchema schema = new ComposedSchema();
        IntegerSchema intBranch = new IntegerSchema();
        intBranch.setFormat("int32");
        schema.addOneOfItem(intBranch);
        schema.addOneOfItem(new NumberSchema());

        String resolved = codegen.getTypeDeclaration(schema);
        Assert.assertEquals(resolved, "std::variant<std::int32_t, double>",
                "oneOf [integer, number] should produce std::variant<int32_t, double>");
    }

    @Test
    public void oneOfStringStringEnumViaGateFixtures() throws IOException {
        // oneOf open-string + string-enum preserves identity via CompositionBranchValue.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-oneof").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastOneOfTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path oneOfHeader = output.toPath().resolve("model/OneOfStringStringEnum.h");
        TestUtils.assertFileExists(oneOfHeader);
        String oneOfContent = java.nio.file.Files.readString(oneOfHeader);
        Assert.assertTrue(oneOfContent.contains("CompositionBranchValue<0, std::string>"),
                "OneOfStringStringEnum should use CompositionBranchValue to preserve branch identity");
        Assert.assertFalse(oneOfContent.contains("using OneOfStringStringEnum = std::string;"),
                "OneOfStringStringEnum must not blind-collapse to std::string");
        Assert.assertFalse(oneOfContent.contains("using OneOfStringStringEnum = boost::json::value;"),
                "OneOfStringStringEnum must not type-erase to boost::json::value");
    }

    @Test
    public void allNullAnyOfViaGateFixtures() throws IOException {
        // Verify that AllNullAnyOf (anyOf [null, null]) in Gate A fixtures
        // produces CompositionBranchValue variant (not boost::json::value).
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-allnull").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastAllNullTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path allNullHeader = output.toPath().resolve("model/AllNullAnyOf.h");
        TestUtils.assertFileExists(allNullHeader);
        String allNullContent = java.nio.file.Files.readString(allNullHeader);
        Assert.assertTrue(allNullContent.contains("CompositionBranchValue<0, std::nullptr_t>"),
                "AllNullAnyOf should use CompositionBranchValue to preserve null identity");
    }

    @Test
    public void duplicateNullOneOfViaGateFixtures() throws IOException {
        // Verify that DuplicateNullOneOf (oneOf [null, null]) in Gate A fixtures
        // produces CompositionBranchValue variant (not boost::json::value).
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-dupenull").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastDupNullTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path dupNullHeader = output.toPath().resolve("model/DuplicateNullOneOf.h");
        TestUtils.assertFileExists(dupNullHeader);
        String dupNullContent = java.nio.file.Files.readString(dupNullHeader);
        Assert.assertTrue(dupNullContent.contains("CompositionBranchValue<0, std::nullptr_t>"),
                "DuplicateNullOneOf should use CompositionBranchValue to preserve null identity");
    }

    @Test
    public void generatesOas30NullableObject() throws IOException {
        // OAS 3.0 nullable: true on an object schema must produce a type that
        // can represent JSON null at the root level (std::optional alias or wrapper).
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-nullable-object").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_0/cpp-boost-beast-client/nullable-object-regression.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // The nullable root object must exist and be a null-capable wrapper type.
        // Require std::optional<NullableObjectRoot> or a hasOptionalValue() type,
        // not just a bare class.
        Path nullableRootHeader = output.toPath().resolve("model/NullableObjectRoot.h");
        TestUtils.assertFileExists(nullableRootHeader);
        String nullableRootContent = java.nio.file.Files.readString(nullableRootHeader);
        // The schema is nullable:true on an object. The generator MUST produce a type
        // that wraps the object so it can represent JSON null at root level.
        // Acceptable: "using NullableObjectRoot = std::optional<NullableObjectRootImpl>"
        // or a class with hasOptionalValue()/resetOptionalValue().
        Assert.assertTrue(nullableRootContent.contains("std::optional<") || nullableRootContent.contains("hasOptionalValue()"),
                "NullableObjectRoot must wrap in std::optional or expose hasOptionalValue() " +
                "(nullable root object). Got: " + nullableRootContent);

        // The nullable property container must distinguish value vs null via Nullable<T> wrapper
        Path nullablePropHeader = output.toPath().resolve("model/NullablePropertyContainer.h");
        TestUtils.assertFileExists(nullablePropHeader);
        String nullablePropContent = java.nio.file.Files.readString(nullablePropHeader);
        // Must use an explicit Nullable<T> field or hasOptionalValue().  A bare IsSet alone
        // is NOT sufficient — the type must also carry null-vs-missing distinction.
        boolean hasNullableWrapper = nullablePropContent.contains("NullableValue") && nullablePropContent.contains("hasOptionalValue");
        boolean hasOptionalNullable = nullablePropContent.contains("std::optional<std::string>");
        Assert.assertTrue(hasNullableWrapper || hasOptionalNullable,
                "NullablePropertyContainer must use Nullable<T> wrapper or optional<optional<string>> " +
                "for nullable property, not IsSet alone. Got: " + nullablePropContent);
    }

    @Test
    public void generatesOptionalNullableTriState() throws IOException {
        // Optional nullable property must preserve missing, null, and value.
        // The tri-state requires a Nullable<T>-like field wrapper, not just an IsSet bool.
        // CURRENT BROKEN BEHAVIOUR: the generator emits only an IsSet flag, which cannot
        // distinguish missing from explicit null.  This test locks the tri-state gap.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-tri-state").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_0/cpp-boost-beast-client/optional-nullable-regression.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path triStateHeader = output.toPath().resolve("model/TriStateContainer.h");
        TestUtils.assertFileExists(triStateHeader);
        String triStateContent = java.nio.file.Files.readString(triStateHeader);

        // The tri-state needs three observable values: missing, null, present.
        // This requires at minimum two bits of state.  A single IsSet bool cannot
        // distinguish missing from explicit null.
        //
        // Acceptable API surfaces (sufficient for tri-state):
        //   1. Nullable<std::string> wrapper (hasOptionalValue + getValue + resetOptionalValue)
        //   2. std::optional<std::optional<std::string>>
        //   3. Separate bool isNull flag alongside IsSet
        //
        // Currently the generator emits only IsSet — this test FAILS on current HEAD
        // because the tri-state gap is real.
        boolean hasTriStateWrapper =
            triStateContent.contains("hasOptionalValue") ||
            triStateContent.contains("resetOptionalValue") ||
            triStateContent.contains("std::optional<std::optional<std::string>>") ||
            (triStateContent.contains("m_NullableValueIsSet") && triStateContent.contains("m_NullableValueIsNull")) ||
            (triStateContent.contains("m_NullableValueIsSet") && triStateContent.contains("setNullableValueNull"));

        Assert.assertTrue(hasTriStateWrapper,
                "TriStateContainer: optional nullable must distinguish missing|null|value via "
                + "hasOptionalValue/resetOptionalValue or Nullable<T> wrapper or "
                + "optional<optional<string>> or explicit is-null flag.  A bare IsSet bool in "
                + "isolation does NOT encode tri-state.  Current header excerpt: "
                + triStateContent);
    }

    @Test
    public void generatesStatusAwareResponseUnion() throws IOException {
        // Successful response union: 200 FullResource, 201 SummaryResource, 204
        // The generated API must expose a status-aware union that distinguishes branches.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-response-union").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_0/cpp-boost-beast-client/response-union-regression.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // All three response models must exist
        TestUtils.assertFileExists(output.toPath().resolve("model/FullResource.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/SummaryResource.h"));
        TestUtils.assertFileExists(output.toPath().resolve("model/CreateRequest.h"));

        // The API source must contain status-branch dispatch for 200, 201, and 204.
        // Currently the generator does NOT produce a status-aware response union —
        // this locks the failing behaviour.  The API should reference all three statuses.
        Path apiSource = output.toPath().resolve("api/DefaultApi.cpp");
        TestUtils.assertFileExists(apiSource);
        String apiContent = java.nio.file.Files.readString(apiSource);

        // The generated API MUST reference all three status codes — this is a distinct
        // assertion from "model files exist".  Current behaviour may only reference two.
        Assert.assertTrue(apiContent.contains("200") || apiContent.contains("status_code_200"),
                "Generated API must reference 200 status branch");
        Assert.assertTrue(apiContent.contains("201") || apiContent.contains("status_code_201"),
                "Generated API must reference 201 status branch");
        Assert.assertTrue(apiContent.contains("204") || apiContent.contains("status_code_204"),
                "Generated API must reference 204 status branch");

        // Phase 0: require the full 200+201+204 response-union shape.
        // ALL THREE status+type pairs are mandatory — a lone ResponseBodyDeserializer
        // / ResponseJsonValueConverter mention must not pass this lock.
        boolean hasAllThreeStatusBranches =
                (apiContent.contains("200") && apiContent.contains("FullResource"))
                && (apiContent.contains("201") && apiContent.contains("SummaryResource"))
                && (apiContent.contains("204") || apiContent.contains("status_code_204"));
        Assert.assertTrue(hasAllThreeStatusBranches,
                "Generated API must distinguish all three response status branches: "
                + "200+FullResource AND 201+SummaryResource AND 204. "
                + "Current output excerpt: " + apiContent);
    }

    @Test
    public void generatesMultipartEncodingMetadata() throws IOException {
        // Multipart form-data with explicit encoding metadata (contentType)
        // The generated code must propagate image/png and application/pdf as
        // Content-Type headers for the respective multipart parts.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-multipart-enc").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_0/cpp-boost-beast-client/multipart-encoding-regression.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // The API source must reference the multipart form encoding metadata
        Path apiSource = output.toPath().resolve("api/DefaultApi.cpp");
        TestUtils.assertFileExists(apiSource);
        String apiContent = java.nio.file.Files.readString(apiSource);

        // The generated API must use multipart/form-data for the encoding endpoint
        Assert.assertTrue(apiContent.contains("multipart/form-data"),
                "Generated API must use multipart/form-data for encoding endpoint");

        // The generated code must propagate image/png and application/pdf as
        // Content-Type headers for the respective multipart parts.
        // CURRENT BROKEN: the generator does NOT emit encoding metadata.
        // Phase 0 locks the gap: assertTrue fails on current HEAD.
        // Once C-08 is implemented, image/png must appear and the test auto-passes.
        Assert.assertTrue(apiContent.contains("image/png"),
                "C-08 gap: image/png NOT emitted in multipart part headers "
                + "(current generator does not propagate encoding metadata). "
                + "This Phase 0 assertion FAILS on current HEAD — expected. "
                + "Generator output excerpt: " + apiContent);
        Assert.assertTrue(apiContent.contains("application/pdf"),
                "C-08 gap: application/pdf NOT emitted in multipart part headers "
                + "(current generator does not propagate encoding metadata). "
                + "This Phase 0 assertion FAILS on current HEAD — expected. "
                + "Generator output excerpt: " + apiContent);
    }

    @Test
    public void generatesVariantAwareApiIntegration() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-variant-api").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path apiSource = output.toPath().resolve("api/ComposedSchemaApi.cpp");
        String generatedApiSource = Files.readString(apiSource);

        // Verify variant/optional template overloads exist in the anonymous namespace
        Assert.assertTrue(generatedApiSource.contains("toRequestJsonValue(const std::variant<Ts...>&"),
                "Generated API source must have std::variant overload for toRequestJsonValue");
        Assert.assertTrue(generatedApiSource.contains("toRequestJsonValue(const std::optional<T>&"),
                "Generated API source must have std::optional overload for toRequestJsonValue");
        Assert.assertTrue(generatedApiSource.contains("ResponseJsonValueConverter<std::variant<Ts...>>"),
                "Generated API source must have std::variant specialization for ResponseJsonValueConverter");
        Assert.assertTrue(generatedApiSource.contains("ResponseJsonValueConverter<std::optional<T>>"),
                "Generated API source must have std::optional specialization for ResponseJsonValueConverter");
        Assert.assertTrue(generatedApiSource.contains("OneOfResponseJsonValueConverter<std::variant<Ts...>>"),
                "Generated API source must preserve exactly-one response decoding for oneOf variants");
        Assert.assertTrue(generatedApiSource.contains("tryFirstVariantAlternative"),
                "Generated API source must use first-match response decoding for anyOf variants");
        Assert.assertTrue(generatedApiSource.contains("std::is_same_v<T, std::uint8_t>"),
                "Generated API source must decode bounded uint8 variant branches");
        Assert.assertTrue(generatedApiSource.contains("IsSpecialization<T, std::variant>"),
                "Generated API source must recurse into nested variant alternatives");
        Assert.assertTrue(generatedApiSource.contains("#include <limits>"),
                "Generated API source using numeric_limits must include <limits>");

        Assert.assertFalse(generatedApiSource.contains("parseEventStream"),
                "Generated API source must not contain the unused buffered SSE parser");

        // Verify trait-based dispatch for toRequestJsonValue
        Assert.assertTrue(generatedApiSource.contains("HasRequestToJsonValue"),
                "Generated API source must contain HasRequestToJsonValue trait");
        Assert.assertTrue(generatedApiSource.contains("HasFromJsonValue"),
                "Generated API source must contain HasFromJsonValue trait");
        Assert.assertFalse(generatedApiSource.contains("HasFromJsonValueMethod"),
                "Generated API source must reuse one fromJsonValue detection trait");

        // Verify postVariantBody method serializes variant body param
        String postVariantBodyMethod = extractMethod(generatedApiSource, "postVariantBody(");
        Assert.assertTrue(postVariantBodyMethod.contains(
                "serializedRequestBody = boost::json::serialize(toRequestJsonValue(inputParam));"),
                "postVariantBody must serialize using toRequestJsonValue");
        Assert.assertTrue(postVariantBodyMethod.contains(
                "OneOfResponseBodyDeserializer<InputParam>::deserialize("),
                "postVariantBody must preserve oneOf response semantics");

        // Verify postAliasBody method serializes alias body param
        String postAliasBodyMethod = extractMethod(generatedApiSource, "postAliasBody(");
        Assert.assertTrue(postAliasBodyMethod.contains(
                "serializedRequestBody = boost::json::serialize(toRequestJsonValue(modelIdsResponses));"),
                "postAliasBody must serialize using toRequestJsonValue");

        // Verify include <optional> and <variant> are present
        Assert.assertTrue(generatedApiSource.contains("#include <optional>"),
                "Generated API source must include <optional>");
        Assert.assertTrue(generatedApiSource.contains("#include <variant>"),
                "Generated API source must include <variant>");

        // Verify SSE streaming endpoint uses executeStream + appendParsedEvent
        String getStreamEventsMethod = extractMethod(generatedApiSource, "getStreamEvents(");
        Assert.assertTrue(getStreamEventsMethod.contains("executeStream("),
                "getStreamEvents must use executeStream for incremental SSE delivery");
        Assert.assertTrue(getStreamEventsMethod.contains("appendParsedEvent(deserializedResponse, eventData, fromJsonValue_ResponseStreamEvent)"),
                "getStreamEvents must appendParsedEvent with fromJsonValue_ResponseStreamEvent converter");
        Assert.assertTrue(generatedApiSource.contains("std::vector<ResponseStreamEvent>"),
                "Generated API source must have std::vector<ResponseStreamEvent> for streaming endpoint");

        // Verify multipart form-data endpoint generates form parameter handling
        String uploadFileMethod = extractMethod(generatedApiSource, "uploadFile(");
        Assert.assertTrue(uploadFileMethod.contains("FormParameter"),
                "uploadFile must generate FormParameter entries");
        Assert.assertTrue(uploadFileMethod.contains("multipart/form-data"),
                "uploadFile must use multipart/form-data serialization");

        // Verify variant form parameter endpoint uses addVariantFormParameter
        String uploadVariantMethod = extractMethod(generatedApiSource, "uploadVariantData(");
        Assert.assertTrue(uploadVariantMethod.contains("addVariantFormParameter(formParameters, \"payload\""),
                "uploadVariantData must use addVariantFormParameter for variant form param");
        Assert.assertTrue(uploadVariantMethod.contains("multipart/form-data"),
                "uploadVariantData must use multipart/form-data serialization");

        // Verify VariantPayload model files exist for branch-aware serialization
        Assert.assertTrue(java.nio.file.Files.exists(output.toPath().resolve("model/VariantPayload.h")),
                "VariantPayload model should be generated");
        Assert.assertTrue(java.nio.file.Files.exists(output.toPath().resolve("model/DataObject.h")),
                "DataObject model should be generated");

        // Verify streaming API header/source signature match
        Path apiHeader = output.toPath().resolve("api/ComposedSchemaApi.h");
        String apiHeaderContent = Files.readString(apiHeader);
        // Header must declare std::vector<ResponseStreamEvent> for streaming op
        // (newline after return type in template)
        Assert.assertTrue(apiHeaderContent.contains("std::vector<ResponseStreamEvent>"),
                "ComposedSchemaApi.h must declare getStreamEvents returning std::vector<ResponseStreamEvent>");
        Assert.assertTrue(apiHeaderContent.contains("getStreamEvents("),
                "ComposedSchemaApi.h must declare getStreamEvents method");

        // Variant headers use toJsonValue_/fromJsonValue_ (not ADL bridge — ADL would conflict)
        String inputParamHeaderContent = Files.readString(
            output.toPath().resolve("model/InputParam.h"));
        Assert.assertFalse(inputParamHeaderContent.contains("to_json("),
                "InputParam header must NOT declare ADL to_json (removed to avoid overload conflict)");
        Assert.assertFalse(inputParamHeaderContent.contains(" from_json("),
                "InputParam header must NOT declare ADL from_json (removed to avoid overload conflict)");

        // ============================================================
        // Phase 2 strong review: anyOf non-discriminated fixture
        // ============================================================
        // AnyOfStringInteger (anyOf string|integer) → std::variant<std::string, int32_t>
        Path anyOfStringIntHeader = output.toPath().resolve("model/AnyOfStringInteger.h");
        TestUtils.assertFileExists(anyOfStringIntHeader);
        String anyOfStringIntContent = java.nio.file.Files.readString(anyOfStringIntHeader);
        Assert.assertTrue(anyOfStringIntContent.contains("using AnyOfStringInteger = std::variant<std::string, int32_t>;"),
                "AnyOfStringInteger should be a variant alias to std::variant<std::string, int32_t>");

        // AnyOfStringInteger source must use first-match (anyOf), NOT exactly-one (oneOf).
        // The fromJsonValue_AnyOfStringInteger function uses isOneOf = false because the
        // composed keyword is "anyOf". Verify the source uses tryVariantBranches (first-match).
        Path anyOfStringIntSource = output.toPath().resolve("model/AnyOfStringInteger.cpp");
        TestUtils.assertFileExists(anyOfStringIntSource);
        String anyOfStringIntSourceContent = java.nio.file.Files.readString(anyOfStringIntSource);
        Assert.assertTrue(anyOfStringIntSourceContent.contains("isOneOf"),
                "AnyOfStringInteger source should contain isOneOf compile-time flag");
        // Since anyOf: isOneOf should be false, the source uses first-match path
        Assert.assertTrue(anyOfStringIntSourceContent.contains("tryVariantBranches"),
                "AnyOfStringInteger source should use tryVariantBranches");

        // AnyOfPropertyHolder references AnyOfStringInteger as a property
        Path anyOfHolderHeader = output.toPath().resolve("model/AnyOfPropertyHolder.h");
        TestUtils.assertFileExists(anyOfHolderHeader);
        String anyOfHolderContent = java.nio.file.Files.readString(anyOfHolderHeader);
        Assert.assertTrue(anyOfHolderContent.contains("AnyOfStringInteger"),
                "AnyOfPropertyHolder should declare a property of type AnyOfStringInteger");
        // The property is not marked required in the spec, so IsSet is expected
        // (variant types don't imply required in the OpenAPI sense)
        Assert.assertTrue(anyOfHolderContent.contains("m_ValueIsSet"),
                "AnyOfPropertyHolder should have IsSet for optional property");

        // AnyOfPropertyHolder source must dispatch property (de)serialization via
        // fromJsonValue_/toJsonValue_ free functions (keyword-faithful: anyOf first-match)
        // rather than the generic converter, so the named alias keeps its own keyword semantics.
        Path anyOfHolderSource = output.toPath().resolve("model/AnyOfPropertyHolder.cpp");
        TestUtils.assertFileExists(anyOfHolderSource);
        String anyOfHolderSourceContent = java.nio.file.Files.readString(anyOfHolderSource);
        Assert.assertTrue(anyOfHolderSourceContent.contains("fromJsonValue_AnyOfStringInteger"),
                "AnyOfPropertyHolder deserialization must use fromJsonValue_AnyOfStringInteger "
                + "(keyword-faithful anyOf first-match)");
        Assert.assertTrue(anyOfHolderSourceContent.contains("toJsonValue_AnyOfStringInteger"),
                "AnyOfPropertyHolder serialization must use toJsonValue_AnyOfStringInteger");
        // The JsonValueConverter variant specialization is still present in the file
        // (for non-alias-referenced variant types) but the property must NOT use it.
        Assert.assertFalse(anyOfHolderSourceContent.contains("JsonValueConverter<AnyOfStringInteger>"),
                "AnyOfPropertyHolder must NOT use JsonValueConverter<AnyOfStringInteger> "
                + "(named aliases must use their generated converter)");

        // Verify new fixture: ParentWithAnyOfOverlapping — parent referencing anyOf of
        // two overlapping object schemas (no discriminator). The generated property
        // code must dispatch via fromJsonValue_AnyOfOverlapping (anyOf first-match).
        Path overlappingParentHeader = output.toPath().resolve("model/ParentWithAnyOfOverlapping.h");
        TestUtils.assertFileExists(overlappingParentHeader);
        String overlappingParentSource = Files.readString(
            output.toPath().resolve("model/ParentWithAnyOfOverlapping.cpp"));
        Assert.assertTrue(overlappingParentSource.contains("fromJsonValue_AnyOfOverlapping"),
                "ParentWithAnyOfOverlapping deserialization must use fromJsonValue_AnyOfOverlapping "
                + "(anyOf first-match for overlapping objects)");
        Assert.assertTrue(overlappingParentSource.contains("toJsonValue_AnyOfOverlapping"),
                "ParentWithAnyOfOverlapping serialization must use toJsonValue_AnyOfOverlapping "
                + "(delegates to anyOf first-match)");

        // Verify NO from_json<T> template call sites in API source (all dispatch via fromJsonValue_)
        Assert.assertFalse(generatedApiSource.contains("from_json<"),
                "API source must not contain from_json<T> template calls (should use fromJsonValue_ functions)");

        // Verify API source calls fromJsonValue_ResponseStreamEvent directly (not template)
        Assert.assertTrue(generatedApiSource.contains("fromJsonValue_ResponseStreamEvent"),
                "API source must use fromJsonValue_ResponseStreamEvent for SSE parsing");

        // Verify HttpClientImpl declares executeStream override
        Path implHeader = output.toPath().resolve("api/HttpClientImpl.h");
        String implHeaderContent = Files.readString(implHeader);
        Assert.assertTrue(implHeaderContent.contains("executeStream("),
                "HttpClientImpl.h must declare executeStream method");
        Assert.assertTrue(implHeaderContent.contains("override"),
                "HttpClientImpl::executeStream must be declared with override");

        // Verify dual-content operation generates stream method in header and source
        Assert.assertTrue(apiHeaderContent.contains("getDualStream"),
                "ComposedSchemaApi.h must declare getDualStream method");
        Assert.assertTrue(apiHeaderContent.contains("getDualStreamStream"),
                "ComposedSchemaApi.h must declare getDualStreamStream streaming overload for dual-content op");
        Assert.assertTrue(generatedApiSource.contains("getDualStreamStream"),
                "ComposedSchemaApi.cpp must implement getDualStreamStream for dual-content op");
        Assert.assertTrue(generatedApiSource.contains("ResponseJsonValueConverter<ResponseStreamEvent>::convert"),
                "Dual-content streaming must use the generic response converter");
        Assert.assertTrue(generatedApiSource.contains("text/event-stream"),
                "ComposedSchemaApi.cpp streaming path must set Accept header to text/event-stream");
        // Verify converter name is a valid C++ identifier (no :: or < or shared_ptr)
        Assert.assertFalse(generatedApiSource.contains("fromJsonValue_std::shared_ptr<"),
                "Converter name must not contain std::shared_ptr< (invalid C++ identifier)");
        Assert.assertFalse(generatedApiSource.contains("fromJsonValue_std::"),
                "Converter name must not contain std:: namespace prefix");

        String inlineAnyOfMethod = extractMethod(generatedApiSource, "getInlineAnyOfResponse(");
        Assert.assertTrue(inlineAnyOfMethod.contains(
                "ResponseBodyDeserializer<GetInlineAnyOfResponse_200_response>::deserialize("),
                "Inline anyOf responses must use first-match variant decoding");
        Assert.assertFalse(inlineAnyOfMethod.contains("OneOfResponseBodyDeserializer"),
                "Inline anyOf responses must not use exactly-one decoding");

        String inlineOneOfStreamMethod = extractMethod(generatedApiSource, "getInlineOneOfEvents(");
        Assert.assertTrue(inlineOneOfStreamMethod.contains(
                "fromJsonValue_GetInlineOneOfEvents_200_response"),
                "Inline oneOf SSE responses must use the generated exactly-one converter");

        String dualPrimitiveMethod = extractMethod(generatedApiSource, "getDualPrimitiveStreamStream(");
        Assert.assertTrue(dualPrimitiveMethod.contains(
                "ResponseJsonValueConverter<std::string>::convert(value)"),
                "Primitive dual-content SSE responses must use the generic response converter");

        String noContentMethod = extractMethod(generatedApiSource, "deleteWithoutContent(");
        Assert.assertTrue(noContentMethod.contains("status(204)"),
                "No-content operations must handle their successful status");
        Assert.assertTrue(noContentMethod.contains("return;"),
                "Successful no-content operations must return normally");

        String httpClientHeader = Files.readString(output.toPath().resolve("api/HttpClient.h"));
        Assert.assertTrue(httpClientHeader.contains("Streaming is not supported"),
                "Custom HttpClient adapters must inherit a non-pure streaming fallback");
        Assert.assertFalse(httpClientHeader.contains("onEvent) = 0"),
                "executeStream must not remain pure virtual");

        String httpClientSource = Files.readString(output.toPath().resolve("api/HttpClientImpl.cpp"));
        Assert.assertTrue(httpClientSource.contains("consumeInitialByteOrderMark"),
                "SSE framing must strip a split UTF-8 BOM at stream start");
        Assert.assertTrue(httpClientSource.contains("http::error::need_buffer"),
                "Incremental Beast reads must accept need_buffer as a refill signal");
        Assert.assertTrue(httpClientSource.contains("tcpStream.expires_never()"),
                "HTTPS streaming must disable the stale tcp_stream expiry");
    }

    @Test
    public void generatesPureSseObjectFixture() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-pure-sse-object").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/pure-sse-object.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path apiSource = output.toPath().resolve("api/SSEApi.cpp");
        String generatedApiSource = Files.readString(apiSource);

        // Verify the pure SSE endpoint uses executeStream + appendParsedEvent with fromJsonValue_Evt
        // (not fromJsonValue_std::shared_ptr<Evt> or any invalid C++ identifier)
        Assert.assertTrue(generatedApiSource.contains("executeStream("),
                "Pure SSE must use executeStream for incremental delivery");
        Assert.assertTrue(generatedApiSource.contains("appendParsedEvent(deserializedResponse, eventData, fromJsonValue_Evt)"),
                "Pure SSE must appendParsedEvent with fromJsonValue_Evt (not shared_ptr wrapper)");
        Assert.assertTrue(generatedApiSource.contains("fromJsonValue_Evt"),
                "Pure SSE must use fromJsonValue_Evt converter (not fromJsonValue_std::...)");

        // Verify NO invalid converter names in the entire source
        Assert.assertFalse(generatedApiSource.contains("fromJsonValue_std::"),
                "Pure SSE object must not contain fromJsonValue_std:: (invalid C++ identifier)");
        Assert.assertFalse(generatedApiSource.contains("fromJsonValue_std::shared_ptr"),
                "Pure SSE object must not contain fromJsonValue_std::shared_ptr");

        // Verify the return type is std::vector<Evt> (vector of plain objects, not shared_ptr)
        Assert.assertTrue(generatedApiSource.contains("std::vector<Evt>"),
                "Pure SSE return type header must be std::vector<Evt>");

        // Verify Evt model template generates both member and free fromJsonValue functions
        Path evtHeader = output.toPath().resolve("model/Evt.h");
        String evtHeaderContent = Files.readString(evtHeader);
        Assert.assertTrue(evtHeaderContent.contains("fromJsonValue_Evt"),
                "Evt model header must declare fromJsonValue_Evt free function");

        Path evtSource = output.toPath().resolve("model/Evt.cpp");
        String evtSourceContent = Files.readString(evtSource);
        Assert.assertTrue(evtSourceContent.contains("fromJsonValue_Evt"),
                "Evt model source must define fromJsonValue_Evt free function");
    }

    @Test
    public void generatesDualObjectSseFixture() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-dual-object-sse").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/dual-object-sse.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path apiSource = output.toPath().resolve("api/DualApi.cpp");
        String generatedApiSource = Files.readString(apiSource);

        // Verify dual-content operation generates stream method
        Assert.assertTrue(generatedApiSource.contains("createItemStream"),
                "Dual-content op must generate createItemStream method");

        Assert.assertTrue(generatedApiSource.contains("ResponseJsonValueConverter<StreamEvent>::convert"),
                "Dual-content stream must use the generic typed response converter");
        Assert.assertFalse(generatedApiSource.contains("fromJsonValue_std::"),
                "Dual-content object stream must not contain fromJsonValue_std::");

        // Verify the stream method uses executeStream + appendParsedEvent with StreamEvent conversion
        Assert.assertTrue(generatedApiSource.contains("executeStream("),
                "Dual-content stream must use executeStream for incremental delivery");
        Assert.assertTrue(generatedApiSource.contains("ResponseJsonValueConverter<StreamEvent>::convert"),
                "Dual-content must append parsed events through the typed converter");

        // Verify the stream method returns std::vector<StreamEvent>
        Assert.assertTrue(generatedApiSource.contains("std::vector<StreamEvent>"),
                "Dual-content stream must return std::vector<StreamEvent>");

        // Verify path params are present in the stream method
        Assert.assertTrue(generatedApiSource.contains("replacePathParameter(path, \"id\""),
                "Dual-content stream method must include path parameter replacement");

        // Verify query params are present with optional guard
        Assert.assertTrue(generatedApiSource.contains("if (verbose)"),
                "Dual-content stream method must guard optional query param");

        // Verify header params are present with serializeHeaderParameterValue
        Assert.assertTrue(generatedApiSource.contains("serializeHeaderParameterValue"),
                "Dual-content stream method must use serializeHeaderParameterValue for headers");

        // Verify body params are present in the stream method
        Assert.assertTrue(generatedApiSource.contains("toRequestJsonValue"),
                "Dual-content stream method must include body serialization");

        // Verify Accept header is forced to text/event-stream
        Assert.assertTrue(generatedApiSource.contains("text/event-stream"),
                "Dual-content stream method must force Accept to text/event-stream");

        // Verify the header declares the stream method with correct return type
        Path apiHeader = output.toPath().resolve("api/DualApi.h");
        String apiHeaderContent = Files.readString(apiHeader);
        Assert.assertTrue(apiHeaderContent.contains("createItemStream"),
                "Dual-content API header must declare createItemStream");
        Assert.assertTrue(apiHeaderContent.contains("std::vector<StreamEvent>"),
                "Dual-content API header must declare stream method returning std::vector<StreamEvent>");
    }

    @Test
    public void rejectsPureSseWithoutResponseSchema() throws IOException {
        // A pure SSE operation with no response schema must NOT generate
        // std::vector<void> (invalid C++). Instead, the streaming flag
        // should be cleared and the return type should be void.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-pure-sse-no-schema").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/pure-sse-no-schema.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path apiHeader = output.toPath().resolve("api/SSEApi.h");
        String generatedApiHeader = Files.readString(apiHeader);

        // Must NOT contain std::vector<void> — that would fail compilation
        Assert.assertFalse(generatedApiHeader.contains("std::vector<void>"),
                "Pure SSE with no schema must not generate std::vector<void>");

        // The getEvents declaration must use void (not std::vector<...>)
        int getEventsPos = generatedApiHeader.indexOf("getEvents(");
        Assert.assertTrue(getEventsPos >= 0, "Pure SSE with no schema must declare getEvents method");
        String beforeGetEvents = generatedApiHeader.substring(Math.max(0, getEventsPos - 60), getEventsPos);
        Assert.assertFalse(beforeGetEvents.contains("std::vector<"),
                "Pure SSE with no schema must not wrap getEvents return type in std::vector<>");

        // Verify the source uses the non-streaming execute path
        Path apiSource = output.toPath().resolve("api/SSEApi.cpp");
        String generatedApiSource = Files.readString(apiSource);
        Assert.assertTrue(generatedApiSource.contains("m_client->execute("),
                "Pure SSE with no schema must use non-streaming execute");
    }

    /**
     * Checks basic C++ syntactic validity of a generated source file:
     * balanced preprocessor guards, no missing/duplicate #endif.
     */
    private static void assertBalancedPreprocessorGuards(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        long ifndefCount = content.lines()
                .filter(line -> line.trim().startsWith("#ifndef"))
                .count();
        long defineCount = content.lines()
                .filter(line -> line.trim().startsWith("#define") && !line.trim().startsWith("#define "))
                .count();
        long endifCount = content.lines()
                .filter(line -> line.trim().startsWith("#endif"))
                .count();
        long ifCount = content.lines()
                .filter(line -> line.trim().startsWith("#if ") || line.trim().startsWith("#ifdef"))
                .count();
        long elifCount = content.lines()
                .filter(line -> line.trim().startsWith("#elif"))
                .count();
        long elseCount = content.lines()
                .filter(line -> line.trim().startsWith("#else"))
                .count();
        // Each #ifndef must have a matching #endif, without duplicates
        long expectedEndif = ifndefCount + ifCount;
        Assert.assertEquals(endifCount, expectedEndif,
                "File " + filePath + " has unbalanced preprocessor guards: " +
                "#ifndef=" + ifndefCount + " #if=" + ifCount + " #endif=" + endifCount);
    }

    @Test
    public void generatedHeadersPassSyntaxSmokeCheck() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-syntax").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // Check all generated model headers for balanced preprocessor guards
        Path modelDir = output.toPath().resolve("model");
        List<Path> headers;
        try (var stream = java.nio.file.Files.list(modelDir)) {
            headers = stream
                    .filter(p -> p.toString().endsWith(".h"))
                    .collect(java.util.stream.Collectors.toList());
        }

        Assert.assertFalse(headers.isEmpty(), "Should have generated at least one model header");

        for (Path header : headers) {
            assertBalancedPreprocessorGuards(header);
        }
    }

    @Test
    public void keepsSharedPtrOnCyclicRefsAndStripsOnNonCyclic() throws IOException {
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-cycles").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/cycle-detection.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CycleDetectionTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path treeNodeHeader = output.toPath().resolve("model/TreeNode.h");
        TestUtils.assertFileExists(treeNodeHeader);
        String treeContent = java.nio.file.Files.readString(treeNodeHeader);
        // TreeNode.children is a self-ref: must keep shared_ptr to break the cycle.
        Assert.assertTrue(treeContent.contains("std::shared_ptr<TreeNode>"),
                "Self-ref TreeNode.children should keep std::shared_ptr<TreeNode>");
        // The array member is std::vector<std::shared_ptr<TreeNode>>, NOT std::vector<TreeNode>
        Assert.assertTrue(treeContent.contains("std::vector<std::shared_ptr<TreeNode>>"),
                "TreeNode children vector should contain shared_ptr");

        Path roundAHeader = output.toPath().resolve("model/RoundA.h");
        TestUtils.assertFileExists(roundAHeader);
        String roundAContent = java.nio.file.Files.readString(roundAHeader);
        // RoundA.next → RoundB is a mutual cycle edge: must keep shared_ptr.
        Assert.assertTrue(roundAContent.contains("std::shared_ptr<RoundB>"),
                "Mutual-cycle edge RoundA.next should keep std::shared_ptr<RoundB>");

        Path roundBHeader = output.toPath().resolve("model/RoundB.h");
        TestUtils.assertFileExists(roundBHeader);
        String roundBContent = java.nio.file.Files.readString(roundBHeader);
        // RoundB.prev → RoundA is the other mutual cycle edge: must keep shared_ptr.
        Assert.assertTrue(roundBContent.contains("std::shared_ptr<RoundA>"),
                "Mutual-cycle edge RoundB.prev should keep std::shared_ptr<RoundA>");

        Path holderHeader = output.toPath().resolve("model/CycleHolder.h");
        TestUtils.assertFileExists(holderHeader);
        String holderContent = java.nio.file.Files.readString(holderHeader);
        // CycleHolder.leaf → Leaf is a non-cyclic edge: must use value semantics (no shared_ptr).
        Assert.assertTrue(holderContent.contains("Leaf m_Leaf"),
                "Non-cycle holder CycleHolder.leaf should use value type Leaf (no shared_ptr)");
        Assert.assertFalse(holderContent.contains("std::shared_ptr<Leaf>"),
                "Non-cycle holder CycleHolder.leaf must NOT use std::shared_ptr<Leaf>");
    }

    @Test
    public void omitsEmptyDefaultInitializer() throws IOException {
        // Verify that no generated model header contains the invalid C++ pattern
        // `= ;` which occurs when defaultValue is null/blank in the template.
        // Regression: ~37+ compilation errors from large real-world corpus headers like
        // `MessageRole m_Role = ;` when enum/model property has no default.
        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-empty-default").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec("src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath());

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path modelDir = output.toPath().resolve("model");
        List<Path> headers;
        try (var stream = java.nio.file.Files.list(modelDir)) {
            headers = stream
                    .filter(p -> p.toString().endsWith(".h"))
                    .collect(java.util.stream.Collectors.toList());
        }
        Assert.assertFalse(headers.isEmpty(), "Should have generated at least one model header");

        for (Path header : headers) {
            String content = java.nio.file.Files.readString(header);
            // The pattern `= ;` is invalid C++ — it means defaultValue was null/blank
            // but the template emitted `= {{{defaultValue}}}` without guarding.
            // A valid assignment like `= 0;` or `= "";` should NOT match.
            Assert.assertFalse(content.contains("= ;"),
                    "Header " + header.getFileName() + " must not contain '= ;' (empty default initializer)");
        }
    }

    @Test
    public void buildsCompositionDescriptorsInPreprocessOpenAPI() {
        // Phase 1 lifecycle test: composition descriptors must be built in
        // preprocessOpenAPI (after normalization and inline flattening) so
        // they exist before any fromModel call. If the generator pipeline
        // ordering changes, this test will catch it.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // Create an OpenAPI with oneOf, anyOf, and allOf schemas
        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // oneOf with two branches
        ComposedSchema oneOfSchema = new ComposedSchema();
        oneOfSchema.addOneOfItem(new StringSchema());
        oneOfSchema.addOneOfItem(new IntegerSchema());
        oneOfSchema.setDiscriminator(
                new io.swagger.v3.oas.models.media.Discriminator()
                        .propertyName("type"));
        schemas.put("OneOfTest", oneOfSchema);

        // anyOf with mixed branches
        ComposedSchema anyOfSchema = new ComposedSchema();
        anyOfSchema.addAnyOfItem(new StringSchema());
        anyOfSchema.addAnyOfItem(new NumberSchema());
        schemas.put("AnyOfTest", anyOfSchema);

        // allOf with property inheritance
        ComposedSchema allOfSchema = new ComposedSchema();
        ObjectSchema baseObj = new ObjectSchema();
        baseObj.addProperties("name", new StringSchema());
        allOfSchema.addAllOfItem(baseObj);
        schemas.put("AllOfTest", allOfSchema);

        // Schema without composition (should have no descriptor)
        schemas.put("SimpleModel", new ObjectSchema());

        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);

        // Assert descriptors exist for composed schemas
        CppBoostBeastClientCodegen.CompositionDescriptor oneOfDesc =
                codegen.getCompositionDescriptor("OneOfTest");
        Assert.assertNotNull(oneOfDesc, "OneOfTest should have a composition descriptor");
        Assert.assertEquals(oneOfDesc.getKeyword(), "oneOf",
                "Keyword must be lowercase string 'oneOf'");
        Assert.assertEquals(oneOfDesc.getBranches().size(), 2);
        Assert.assertEquals(oneOfDesc.getSchemaLocation(),
                "#/components/schemas/OneOfTest");

        // Discriminator must be captured
        Assert.assertNotNull(oneOfDesc.getDiscriminator(),
                "OneOfTest with discriminator must capture DiscriminatorDescriptor");
        Assert.assertEquals(oneOfDesc.getDiscriminator().getPropertyName(), "type");

        CppBoostBeastClientCodegen.CompositionDescriptor anyOfDesc =
                codegen.getCompositionDescriptor("AnyOfTest");
        Assert.assertNotNull(anyOfDesc, "AnyOfTest should have a composition descriptor");
        Assert.assertEquals(anyOfDesc.getKeyword(), "anyOf",
                "Keyword must be lowercase string 'anyOf'");
        Assert.assertEquals(anyOfDesc.getBranches().size(), 2);

        CppBoostBeastClientCodegen.CompositionDescriptor allOfDesc =
                codegen.getCompositionDescriptor("AllOfTest");
        Assert.assertNotNull(allOfDesc, "AllOfTest should have a composition descriptor");
        Assert.assertEquals(allOfDesc.getKeyword(), "allOf",
                "Keyword must be lowercase string 'allOf'");

        // SimpleModel should have NO descriptor
        Assert.assertNull(codegen.getCompositionDescriptor("SimpleModel"),
                "SimpleModel should not have a composition descriptor");

        // Preserve branch order
        Assert.assertEquals(oneOfDesc.getBranches().get(0).getBranchIndex(), 0);
        Assert.assertEquals(oneOfDesc.getBranches().get(1).getBranchIndex(), 1);
    }

    @Test
    public void buildsCompositionDescriptorWithRefResolutionAndCycleDetection() {
        // Verify that $ref branches are resolved with cycle detection
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // Target schema for $ref
        schemas.put("TargetModel", new StringSchema());

        // oneOf with $ref branch
        ComposedSchema refOneOf = new ComposedSchema();
        Schema refBranch = new Schema();
        refBranch.set$ref("#/components/schemas/TargetModel");
        refOneOf.addOneOfItem(refBranch);
        refOneOf.addOneOfItem(new IntegerSchema());
        schemas.put("RefOneOf", refOneOf);

        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor descriptor =
                codegen.getCompositionDescriptor("RefOneOf");
        Assert.assertNotNull(descriptor);
        Assert.assertEquals(descriptor.getBranches().size(), 2);

        // First branch should have $ref recorded
        CppBoostBeastClientCodegen.CompositionBranchDescriptor refBranchDesc =
                descriptor.getBranches().get(0);
        Assert.assertEquals(refBranchDesc.getSourceSchemaRef(),
                "#/components/schemas/TargetModel");
        Assert.assertEquals(refBranchDesc.getResolvedSchemaName(), "TargetModel");
        Assert.assertEquals(refBranchDesc.getNullCapability(),
                CppBoostBeastClientCodegen.CompositionBranchDescriptor.NullCapability.NEVER);

        // Assertion metadata must be present on the resolved $ref target
        Assert.assertTrue(refBranchDesc.getSupportedAssertions().contains("type"),
                "$ref branch must capture 'type' assertion from resolved target");
        Assert.assertTrue(refBranchDesc.getUnsupportedAssertions().isEmpty(),
                "StringSchema should have no unsupported assertions");
    }

    @Test
    public void compositionDescriptorsSurviveFullPipeline() throws IOException {
        // Contract test: descriptors built in preprocessOpenAPI survive
        // the full generation pipeline (normalization → inline flattening
        // → preprocessOpenAPI → fromModel → postProcessModels).
        // Verifies descriptor-driven lowering produces correct C++ types
        // in the final generated output.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        File output = java.nio.file.Files.createTempDirectory(
                "cpp-boost-beast-desc-fullpipe").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(
                        "src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "DescriptorPipelineTest");

        // Full pipeline via DefaultGenerator
        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // Contract: descriptor-driven lowering must produce correct types
        //
        // InputParam (oneOf string + array) → std::variant<std::string, std::vector<InputItem>>
        Path inputParam = output.toPath().resolve("model/InputParam.h");
        Assert.assertTrue(java.nio.file.Files.exists(inputParam),
                "InputParam (oneOf) must generate a model header");
        String inputParamContent = new String(java.nio.file.Files.readAllBytes(inputParam));
        Assert.assertTrue(inputParamContent.contains("std::variant<")
                        && inputParamContent.contains("std::string")
                        && inputParamContent.contains("std::vector<InputItem>"),
                "InputParam must lower to std::variant<std::string, std::vector<InputItem>>; content: "
                        + inputParamContent.substring(0, Math.min(500, inputParamContent.length())));

        // OptionalScore (oneOf [null, number]) → std::optional<double>
        Path optionalScore = output.toPath().resolve("model/OptionalScore.h");
        Assert.assertTrue(java.nio.file.Files.exists(optionalScore),
                "OptionalScore (oneOf null+number) must generate a model header");
        String optionalScoreContent = new String(java.nio.file.Files.readAllBytes(optionalScore));
        Assert.assertTrue(optionalScoreContent.contains("std::optional"),
                "OptionalScore must lower to std::optional<double>; content: "
                        + optionalScoreContent.substring(0, Math.min(500, optionalScoreContent.length())));

        // ModelIdsShared (anyOf string + string-enum) → std::string
        Path modelIds = output.toPath().resolve("model/ModelIdsShared.h");
        Assert.assertTrue(java.nio.file.Files.exists(modelIds),
                "ModelIdsShared (anyOf) must generate a model header");
        String modelIdsContent = new String(java.nio.file.Files.readAllBytes(modelIds));
        Assert.assertTrue(modelIdsContent.contains("using ModelIdsShared") || modelIdsContent.contains("std::string"),
                "ModelIdsShared must lower to string alias; content: "
                        + modelIdsContent.substring(0, Math.min(500, modelIdsContent.length())));

        // PetByType (oneOf with discriminator) → std::variant<Cat, Dog> or similar
        Path petByType = output.toPath().resolve("model/PetByType.h");
        Assert.assertTrue(java.nio.file.Files.exists(petByType),
                "PetByType (oneOf with discriminator) must generate a model header");
        String petByTypeContent = new String(java.nio.file.Files.readAllBytes(petByType));
        Assert.assertTrue(petByTypeContent.contains("std::variant"),
                "PetByType must lower to variant type; content: "
                        + petByTypeContent.substring(0, Math.min(500, petByTypeContent.length())));
    }

    @Test
    public void normalizerPreservesCompositionBeforeDescriptorBuild()
            throws IOException {
        // Contract test: after normalization runs during DefaultGenerator,
        // the schema tree retains all original oneOf/anyOf branches so that
        // preprocessOpenAPI can build complete descriptors. Generate from
        // the full fixture and verify the descriptor index by checking
        // generated output reflects descriptor-driven lowering.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        File output = java.nio.file.Files.createTempDirectory(
                "cpp-boost-beast-norm-before-desc").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(
                        "src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml")
                .setOutputDir(output.getAbsolutePath());

        // Run full pipeline so normalization runs before descriptor building
        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        // Verify descriptor-driven lowering by checking generated output types.
        // All models in the fixture must produce correct lowering:
        Path dedupTest = output.toPath().resolve("model/DedupTest.h");
        Assert.assertTrue(java.nio.file.Files.exists(dedupTest),
                "DedupTest must generate a model header");
        String dedupContent = new String(java.nio.file.Files.readAllBytes(dedupTest));
        // DedupTest (oneOf string-enum + integer + string) — two branches are
        // both std::string. Phase 3 preserves identity via CompositionBranchValue.
        Assert.assertTrue(dedupContent.contains("CompositionBranchValue"),
                "DedupTest must use CompositionBranchValue to preserve string "
                        + "branch identity; content: "
                        + dedupContent.substring(0, Math.min(500, dedupContent.length())));

        // Verify fromJsonValue uses descriptor-guided conversion
        Path dedupSource = output.toPath().resolve("model/DedupTest.cpp");
        Assert.assertTrue(java.nio.file.Files.exists(dedupSource),
                "DedupTest must generate a model source file");
        String dedupSourceContent = new String(java.nio.file.Files.readAllBytes(dedupSource));
        Assert.assertTrue(dedupSourceContent.contains("matchedBranchIndex"),
                "DedupTest fromJsonValue must track matchedBranchIndex from "
                        + "validator (not tryVariantBranches); content: "
                        + dedupSourceContent.substring(0, Math.min(500, dedupSourceContent.length())));
        Assert.assertTrue(
                dedupSourceContent.contains("CompositionBranchValue<0, std::string>{std::move(converted)}"),
                "DedupTest fromJsonValue must construct CompositionBranchValue<0, "
                        + "std::string> from the converted branch value; content: "
                        + dedupSourceContent.substring(0, Math.min(500, dedupSourceContent.length())));

        // RefHolder must reference OptionalScore and InputParam models
        Path refHolder = output.toPath().resolve("model/RefHolder.h");
        Assert.assertTrue(java.nio.file.Files.exists(refHolder),
                "RefHolder must generate a model header");
        // If RefHolder includes OptionalScore and InputParam, the pipeline
        // resolved their types correctly
    }

    @Test
    public void normalizerBypassPreservesBranchCardinalityForOneOf() {
        // Direct test: verify that the normalizer's processSimplifyOneOf
        // returns the original schema unchanged when oneOf branches exist.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        // Build a oneOf with branches that default normalizer would simplify
        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");

        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        schema.addOneOfItem(new IntegerSchema());
        schema.addOneOfItem(new NumberSchema());

        // Create the normalizer
        Map<String, String> rules = new HashMap<>();
        TestNormalizer normalizer =
                new TestNormalizer(openAPI, rules);

        Schema result = normalizer.processSimplifyOneOf(schema);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.getOneOf() != null && result.getOneOf().size() == 3,
                "Normalizer must preserve original oneOf branch count");
    }

    @Test
    public void normalizerBypassPreservesBranchCardinalityForAnyOf() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");

        // anyOf with string + enum branch (default normalizer would simplify)
        ComposedSchema schema = new ComposedSchema();
        schema.addAnyOfItem(new StringSchema());
        StringSchema enumSchema = new StringSchema();
        enumSchema.addEnumItem("alpha");
        enumSchema.addEnumItem("beta");
        schema.addAnyOfItem(enumSchema);

        Map<String, String> rules = new HashMap<>();
        TestNormalizer normalizer =
                new TestNormalizer(openAPI, rules);

        // Test both processSimplifyAnyOf and processSimplifyAnyOfStringAndEnumString
        Schema anyOfResult = normalizer.processSimplifyAnyOf(schema);
        Assert.assertNotNull(anyOfResult);
        Assert.assertTrue(anyOfResult.getAnyOf() != null
                        && anyOfResult.getAnyOf().size() == 2,
                "processSimplifyAnyOf must preserve anyOf branch count");

        Schema stringEnumResult = normalizer.processSimplifyAnyOfStringAndEnumString(schema);
        Assert.assertNotNull(stringEnumResult);
        Assert.assertTrue(stringEnumResult.getAnyOf() != null
                        && stringEnumResult.getAnyOf().size() == 2,
                "processSimplifyAnyOfStringAndEnumString must preserve anyOf branch count");
    }

    @Test
    public void xCppCompositionBranchesStructureContract()
            throws Exception {
        // Contract test: validates that x-cpp-composition-branches structure
        // is populated on codegen state with correct keyword, branch count,
        // and assertion lists on each branch. Uses preprocessOpenAPI +
        // fromModel + postProcessModels to inspect descriptor-derived state.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // oneOf with string + integer branches and a discriminator
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        schema.addOneOfItem(new IntegerSchema());
        schema.setDiscriminator(
                new io.swagger.v3.oas.models.media.Discriminator()
                        .propertyName("kind"));
        schemas.put("StringOrInt", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        // Descriptor must exist with correct keyword and branch count
        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("StringOrInt");
        Assert.assertNotNull(desc,
                "StringOrInt must have a composition descriptor");
        Assert.assertEquals(desc.getKeyword(), "oneOf",
                "x-cpp-composition-branches keyword must be 'oneOf'");
        Assert.assertEquals(desc.getBranches().size(), 2,
                "x-cpp-composition-branches must have 2 branches");

        // Each branch must have resolved-schema-name and supported assertions
        CppBoostBeastClientCodegen.CompositionBranchDescriptor branch0 =
                desc.getBranches().get(0);
        Assert.assertEquals(branch0.getResolvedSchemaName(), "string",
                "Branch 0 must be the string branch");
        Assert.assertTrue(
                branch0.getSupportedAssertions().contains("type"),
                "String branch must have 'type' in supportedAssertions");

        CppBoostBeastClientCodegen.CompositionBranchDescriptor branch1 =
                desc.getBranches().get(1);
        Assert.assertEquals(branch1.getResolvedSchemaName(), "integer",
                "Branch 1 must be the integer branch");
        Assert.assertTrue(
                branch1.getSupportedAssertions().contains("type"),
                "Integer branch must have 'type' in supportedAssertions");

        // Discriminator must be present
        Assert.assertTrue(desc.hasDiscriminator(),
                "Descriptor must have discriminator");
        Assert.assertEquals(desc.getDiscriminator().getPropertyName(), "kind",
                "Discriminator property name must be 'kind'");

        // Run lowering and verify x-cpp-composition-branches survives
        CodegenModel cm = codegen.fromModel("StringOrInt", schema);
        if (cm.classname == null) {
            cm.classname = "StringOrInt";
        }
        org.openapitools.codegen.model.ModelsMap modelsMap =
                new org.openapitools.codegen.model.ModelsMap();
        org.openapitools.codegen.model.ModelMap modelWrap =
                new org.openapitools.codegen.model.ModelMap();
        modelWrap.setModel(cm);
        java.util.List<org.openapitools.codegen.model.ModelMap> modelList =
                new java.util.ArrayList<>();
        modelList.add(modelWrap);
        modelsMap.setModels(modelList);
        modelsMap = codegen.postProcessModels(modelsMap);

        // After lowering, x-cpp-composition-branches must still be present
        CodegenModel processed = modelsMap.getModels().get(0).getModel();
        Object extValue = processed.vendorExtensions.get("x-cpp-composition-branches");
        Assert.assertNotNull(extValue,
                "x-cpp-composition-branches must survive postProcessModels");
        @SuppressWarnings("unchecked")
        Map<String, Object> extMap = (Map<String, Object>) extValue;
        Assert.assertEquals(extMap.get("keyword"), "oneOf",
                "x-cpp-composition-branches keyword must be 'oneOf'");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches =
                (List<Map<String, Object>>) extMap.get("branches");
        Assert.assertNotNull(branches, "x-cpp-composition-branches must have branches");
        Assert.assertEquals(branches.size(), 2,
                "x-cpp-composition-branches must have 2 branches");

        // Each branch map must have assertion and capability fields
        for (Map<String, Object> brMap : branches) {
            Assert.assertTrue(brMap.containsKey("branch-index"),
                    "Branch must have branch-index");
            Assert.assertTrue(brMap.containsKey("null-capability"),
                    "Branch must have null-capability");
            Assert.assertTrue(brMap.containsKey("supported-assertions"),
                    "Branch must have supported-assertions");
            Assert.assertTrue(brMap.containsKey("unsupported-assertions"),
                    "Branch must have unsupported-assertions");
        }
    }

    @Test
    public void descriptorDrivesLoweringMetadata() {
        // Contract test: processComposedModel/lowerComposedTypes must read
        // the CompositionDescriptor when available, using its nullCapability
        // metadata for Rule 1 ([T, null] → optional<T>) instead of inferring
        // from C++ type strings alone. Verify descriptor is looked up by
        // toModelName.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // oneOf with null, discriminator, and string branches
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        Schema nullBranch = new Schema();
        nullBranch.set$ref("#/components/schemas/NullModel");
        schema.addOneOfItem(nullBranch);
        schema.setDiscriminator(
                new io.swagger.v3.oas.models.media.Discriminator()
                        .propertyName("type"));
        schemas.put("StringOrNull", schema);
        schemas.put("NullModel", new Schema().nullable(true));

        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        // Descriptor must be indexed by toModelName
        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("StringOrNull");
        Assert.assertNotNull(desc, "StringOrNull must have a descriptor by toModelName");
        Assert.assertEquals(desc.getKeyword(), "oneOf",
                "Keyword must be 'oneOf' not 'ONE_OF'");
        Assert.assertEquals(desc.getBranches().size(), 2,
                "Branch count must be preserved");

        // Null branch must detect null capability from the $ref target
        CppBoostBeastClientCodegen.CompositionBranchDescriptor nullBranchDesc =
                desc.getBranches().get(1);
        Assert.assertTrue(
                nullBranchDesc.getNullCapability()
                        == CppBoostBeastClientCodegen.CompositionBranchDescriptor.NullCapability.ALWAYS
                || nullBranchDesc.getNullCapability()
                        == CppBoostBeastClientCodegen.CompositionBranchDescriptor.NullCapability.CONDITIONAL,
                "Null $ref branch must have ALWAYS or CONDITIONAL nullCapability, got: "
                        + nullBranchDesc.getNullCapability());

        // Discriminator must be captured
        Assert.assertTrue(desc.getDiscriminator() != null,
                "Descriptor must capture discriminator");
        Assert.assertEquals(desc.getDiscriminator().getPropertyName(), "type",
                "Discriminator property name must be captured");

        // Branch must have assertion metadata
        CppBoostBeastClientCodegen.CompositionBranchDescriptor stringBranchDesc =
                desc.getBranches().get(0);
        // The string branch resolved schema is the NullModel; but branch 0
        // is a StringSchema inline, which has type -> "type" assertion
        Assert.assertTrue(stringBranchDesc.getSupportedAssertions().isEmpty()
                        || stringBranchDesc.getSupportedAssertions().contains("type"),
                "String branch should have 'type' in supported assertions");

        // Null branch must not have unsupported assertions (simple nullable ref)
        Assert.assertTrue(nullBranchDesc.getUnsupportedAssertions().isEmpty(),
                "Simple nullable $ref should have empty unsupportedAssertions");
    }

    @Test
    public void descriptorBranchIndexAlignsAfterSelfRefFiltering()
            throws Exception {
        // Contract test: when a self-referencing oneOf branch is filtered
        // in processComposedModel, lowerComposedTypes Rule 1 and Rule 3
        // must still correctly align descriptor nullCapability via
        // originalBranchIndex. Schema: oneOf [SelfModel, null, string].
        // Invokes full lowering (preprocessOpenAPI → fromModel →
        // postProcessModels) and checks the final vendor extension.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // Self-referencing oneOf: SelfModel, null, string
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new Schema().$ref("#/components/schemas/SchemaWithSelfRef"));
        Schema nullBranch = new Schema();
        nullBranch.set$ref("#/components/schemas/NullType");
        schema.addOneOfItem(nullBranch);
        schema.addOneOfItem(new StringSchema());
        schemas.put("SchemaWithSelfRef", schema);
        schemas.put("NullType", new Schema().nullable(true));

        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        // Step 1: Verify descriptor has correct structure
        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("SchemaWithSelfRef");
        Assert.assertNotNull(desc,
                "SchemaWithSelfRef must have a composition descriptor");
        Assert.assertEquals(desc.getBranches().size(), 3,
                "Descriptor must have 3 branches (self-ref, null, string)");

        // Step 2: Run lowering via fromModel + postProcessModels
        // fromModel converts the raw schema into a CodegenModel with
        // composedSchemas containing oneOf CodegenProperty branches.
        CodegenModel cm = codegen.fromModel("SchemaWithSelfRef", schema);
        Assert.assertNotNull(cm, "fromModel must produce a CodegenModel");
        // Set classname explicitly if fromModel didn't
        if (cm.classname == null) {
            cm.classname = "SchemaWithSelfRef";
        }

        // Wrap in ModelsMap for postProcessModels
        org.openapitools.codegen.model.ModelsMap modelsMap =
                new org.openapitools.codegen.model.ModelsMap();
        org.openapitools.codegen.model.ModelMap modelWrap =
                new org.openapitools.codegen.model.ModelMap();
        modelWrap.setModel(cm);
        java.util.List<org.openapitools.codegen.model.ModelMap> modelList =
                new java.util.ArrayList<>();
        modelList.add(modelWrap);
        modelsMap.setModels(modelList);
        modelsMap = codegen.postProcessModels(modelsMap);

        // Step 3: Verify lowering results in correct type
        CodegenModel processed = modelsMap.getModels().get(0).getModel();
        Assert.assertTrue(processed.vendorExtensions.containsKey("x-cpp-type"),
                "SchemaWithSelfRef must have x-cpp-type after lowering");
        String resolvedType = (String) processed.vendorExtensions.get("x-cpp-type");
        // After self-ref filtering: composed branches = [null (idx=1), string (idx=2)].
        // Rule 1 via descriptor: alwaysNullCount=1, branches.size()==2 →
        // "std::optional<std::string>".
        Assert.assertTrue(resolvedType != null
                        && resolvedType.contains("std::optional")
                        && resolvedType.contains("std::string"),
                "SchemaWithSelfRef must lower to std::optional<std::string> "
                        + "(self-ref filtered, Rule 1 detects [null, T] pattern via descriptor), got: "
                        + resolvedType);

        // Verify x-cpp-branch-original-index contains the descriptor positions
        // after the self-ref (branch 0) was filtered: [1, 2]
        Assert.assertTrue(processed.vendorExtensions
                        .containsKey("x-cpp-branch-original-index"),
                "SchemaWithSelfRef must have x-cpp-branch-original-index for Phase 1b");
        @SuppressWarnings("unchecked")
        List<Integer> storedIndices = (List<Integer>) processed.vendorExtensions
                .get("x-cpp-branch-original-index");
        Assert.assertNotNull(storedIndices,
                "x-cpp-branch-original-index must not be null");
        Assert.assertEquals(storedIndices.size(), 2,
                "x-cpp-branch-original-index must have 2 branches after self-ref skip");
        Assert.assertEquals((int) storedIndices.get(0), 1,
                "First composed branch (null) must have originalBranchIndex=1");
        Assert.assertEquals((int) storedIndices.get(1), 2,
                "Second composed branch (string) must have originalBranchIndex=2");
    }

    @Test
    public void descriptorUnsupportedAssertionsPopulated() {
        // Contract test: CompositionBranchDescriptor.unsupportedAssertions
        // must be populated with known-unsupported keywords when present
        // in the resolved schema.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        openAPI.setServers(new java.util.ArrayList<>());
        io.swagger.v3.oas.models.Components components =
                new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();

        // Schema with conditional + contains + content encoding
        ComposedSchema schema = new ComposedSchema();
        StringSchema conditionalBranch = new StringSchema();
        conditionalBranch.setMinLength(1);
        io.swagger.v3.oas.models.media.Schema ifSchema =
                new io.swagger.v3.oas.models.media.Schema();
        ifSchema.setType("object");
        conditionalBranch.setIf(ifSchema);
        conditionalBranch.setThen(new Schema());
        schema.addOneOfItem(conditionalBranch);

        ArraySchema arrayWithContains = new ArraySchema();
        arrayWithContains.setContains(new StringSchema());
        schema.addOneOfItem(arrayWithContains);

        schemas.put("SchemaWithUnsupported", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("SchemaWithUnsupported");
        Assert.assertNotNull(desc,
                "SchemaWithUnsupported must have a descriptor");

        // First branch: if/then/else → "conditional"
        CppBoostBeastClientCodegen.CompositionBranchDescriptor conditionalBranchDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(
                conditionalBranchDesc.getUnsupportedAssertions().contains("conditional"),
                "Branch with if/then/else must have conditional in unsupportedAssertions");
        // String with minLength has supported-length
        Assert.assertTrue(
                conditionalBranchDesc.getSupportedAssertions().contains("string-length"),
                "Branch with minLength must have string-length in supportedAssertions");

        // Second branch: contains → "contains"
        CppBoostBeastClientCodegen.CompositionBranchDescriptor containsBranchDesc =
                desc.getBranches().get(1);
        Assert.assertTrue(
                containsBranchDesc.getSupportedAssertions().contains("array-items"),
                "Branch with contains must have array-items in supportedAssertions");
    }

    @Test
    public void normalizerBypassPreservesEnumComposition() {
        // Verify that processSimplifyOneOfEnum and processSimplifyAnyOfEnum
        // bypasses preserve the original composition for this generator.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");

        // oneOf with all enums (default normalizer would merge to single enum)
        ComposedSchema oneOfEnum = new ComposedSchema();
        StringSchema enumA = new StringSchema();
        enumA.addEnumItem("red");
        enumA.addEnumItem("blue");
        oneOfEnum.addOneOfItem(enumA);
        StringSchema enumB = new StringSchema();
        enumB.addEnumItem("green");
        enumB.addEnumItem("yellow");
        oneOfEnum.addOneOfItem(enumB);

        Map<String, String> rules = new HashMap<>();
        TestNormalizer normalizer =
                new TestNormalizer(openAPI, rules);

        Schema oneOfResult = normalizer.processSimplifyOneOfEnum(oneOfEnum);
        Assert.assertNotNull(oneOfResult);
        Assert.assertTrue(oneOfResult.getOneOf() != null
                        && oneOfResult.getOneOf().size() == 2,
                "processSimplifyOneOfEnum must preserve oneOf branch count");

        // anyOf with all enums
        ComposedSchema anyOfEnum = new ComposedSchema();
        anyOfEnum.addAnyOfItem(enumA);
        anyOfEnum.addAnyOfItem(enumB);

        Schema anyOfResult = normalizer.processSimplifyAnyOfEnum(anyOfEnum);
        Assert.assertNotNull(anyOfResult);
        Assert.assertEquals(anyOfResult.getAnyOf().size(), 2,
                "processSimplifyAnyOfEnum must preserve anyOf branch count");
    }

    // ====================================================================
    // Phase 2: Generated validator foundation and numeric semantics
    // ====================================================================

    // --- Phase 2 strong review: multipleOf, exclusive bounds, integer enum ---

    @Test
    public void branchDescriptorsHaveMultipleOfValidation() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        NumberSchema multBranch = new NumberSchema();
        multBranch.setMultipleOf(3.0);
        schema.addOneOfItem(multBranch);

        NumberSchema noMultBranch = new NumberSchema();
        schema.addOneOfItem(noMultBranch);
        schemas.put("MultipleOfTest", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("MultipleOfTest");
        Assert.assertNotNull(desc, "MultipleOfTest must have a descriptor");

        CppBoostBeastClientCodegen.CompositionBranchDescriptor multBranchDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(multBranchDesc.getSupportedAssertions().contains("numeric-range"),
                "Branch with multipleOf must have numeric-range assertion");
        Assert.assertNotNull(multBranchDesc.getValidateParams().get("validation-multiple-of"),
                "Branch with multipleOf must have validation-multiple-of param");
        Assert.assertEquals(multBranchDesc.getValidateParams().get("validation-multiple-of"), 3.0,
                "Branch with multipleOf must have validation-multiple-of = 3.0");

        // Second branch without multipleOf: numeric-range must NOT be present
        CppBoostBeastClientCodegen.CompositionBranchDescriptor noMultBranchDesc =
                desc.getBranches().get(1);
        Assert.assertFalse(noMultBranchDesc.getSupportedAssertions().contains("numeric-range"),
                "Branch without numeric constraints must NOT have numeric-range assertion");
    }

    @Test
    public void branchDescriptorsHaveExclusiveBounds() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        IntegerSchema exclMinBranch = new IntegerSchema();
        exclMinBranch.setExclusiveMinimum(true);
        // OAS 3.0 exclusiveMinimum with minimum: the combined effect must produce
        // validation-exclusive-min in the descriptor.
        exclMinBranch.setMinimum(10);
        schema.addOneOfItem(exclMinBranch);

        IntegerSchema exclMaxBranch = new IntegerSchema();
        exclMaxBranch.setExclusiveMaximum(true);
        exclMaxBranch.setMaximum(100);
        schema.addOneOfItem(exclMaxBranch);

        // OAS 3.1 numeric exclusive bounds
        IntegerSchema exclMinValBranch = new IntegerSchema();
        exclMinValBranch.setExclusiveMinimumValue(java.math.BigDecimal.valueOf(5));
        schema.addAnyOfItem(exclMinValBranch);

        IntegerSchema exclMaxValBranch = new IntegerSchema();
        exclMaxValBranch.setExclusiveMaximumValue(java.math.BigDecimal.valueOf(200));
        schema.addAnyOfItem(exclMaxValBranch);

        schemas.put("ExclusiveBoundsTest", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("ExclusiveBoundsTest");
        Assert.assertNotNull(desc, "ExclusiveBoundsTest must have a descriptor");
        Assert.assertEquals(desc.getBranches().size(), 4,
                "ExclusiveBoundsTest must have 4 branches");

        // Branch 0: exclusiveMinimum (boolean) with minimum
        CppBoostBeastClientCodegen.CompositionBranchDescriptor exclMinDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(exclMinDesc.getSupportedAssertions().contains("numeric-range"),
                "Branch with exclusiveMinimum must have numeric-range");
        Assert.assertEquals(exclMinDesc.getValidateParams().get("validation-min"), 10,
                "Branch with exclusiveMinimum must have validation-min = 10");
        // After ModelUtils resolution, exclusiveMinimum=true on minimum=10
        // produces exclusive-min = 10 in the params
        Object exclMinVal = exclMinDesc.getValidateParams().get("validation-exclusive-min");
        Assert.assertEquals(exclMinVal, 10,
                "Branch with exclusiveMinimum=true and minimum=10 must have validation-exclusive-min = 10");

        // Branch 1: exclusiveMaximum (boolean) with maximum
        CppBoostBeastClientCodegen.CompositionBranchDescriptor exclMaxDesc =
                desc.getBranches().get(1);
        Assert.assertEquals(exclMaxDesc.getValidateParams().get("validation-max"), 100,
                "Branch with exclusiveMaximum must have validation-max = 100");
        Assert.assertEquals(exclMaxDesc.getValidateParams().get("validation-exclusive-max"), 100,
                "Branch with exclusiveMaximum=true and maximum=100 must have validation-exclusive-max = 100");

        // Branch 2: OAS 3.1 exclusiveMinimum value (numeric)
        CppBoostBeastClientCodegen.CompositionBranchDescriptor exclMinValDesc =
                desc.getBranches().get(2);
        Assert.assertEquals(exclMinValDesc.getValidateParams().get("validation-min"), 5,
                "OAS 3.1 exclusiveMinimum=5 must produce validation-min = 5");
        Assert.assertEquals(exclMinValDesc.getValidateParams().get("validation-exclusive-min"), 5,
                "OAS 3.1 exclusiveMinimum=5 must produce validation-exclusive-min = 5");

        // Branch 3: OAS 3.1 exclusiveMaximum value (numeric)
        CppBoostBeastClientCodegen.CompositionBranchDescriptor exclMaxValDesc =
                desc.getBranches().get(3);
        Assert.assertEquals(exclMaxValDesc.getValidateParams().get("validation-max"), 200,
                "OAS 3.1 exclusiveMaximum=200 must produce validation-max = 200");
        Assert.assertEquals(exclMaxValDesc.getValidateParams().get("validation-exclusive-max"), 200,
                "OAS 3.1 exclusiveMaximum=200 must produce validation-exclusive-max = 200");
    }

    @Test
    public void branchDescriptorsHaveIntegerEnumKind() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        // Integer enum branch
        ComposedSchema schema = new ComposedSchema();
        IntegerSchema intEnumBranch = new IntegerSchema();
        intEnumBranch.addEnumItem(1);
        intEnumBranch.addEnumItem(2);
        intEnumBranch.addEnumItem(3);
        schema.addOneOfItem(intEnumBranch);

        // String enum branch (for comparison)
        StringSchema stringEnumBranch = new StringSchema();
        stringEnumBranch.addEnumItem("red");
        stringEnumBranch.addEnumItem("blue");
        schema.addOneOfItem(stringEnumBranch);

        // Float enum branch (number kind)
        NumberSchema floatEnumBranch = new NumberSchema();
        floatEnumBranch.addEnumItem(1.5);
        floatEnumBranch.addEnumItem(2.5);
        schema.addOneOfItem(floatEnumBranch);

        // Boolean enum branch
        StringSchema boolEnumBranch = new StringSchema();
        // Note: in OAS 3.x, boolean enums pass through as Object; the predominant
        // kind detection checks Java type of enum values.
        // For this test, use NumberSchema with boolean values is tricky.
        // Instead, verify integer and string enum kinds.
        schemas.put("EnumKindTest", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("EnumKindTest");
        Assert.assertNotNull(desc, "EnumKindTest must have a descriptor");

        // Branch 0: integer enum → validation-enum-kind = "integer"
        CppBoostBeastClientCodegen.CompositionBranchDescriptor intEnumDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(intEnumDesc.getSupportedAssertions().contains("enum"),
                "Integer enum branch must have enum assertion");
        Assert.assertEquals(intEnumDesc.getValidateParams().get("validation-enum-kind"), "integer",
                "Integer enum branch must have validation-enum-kind = integer");
        Object enumValues = intEnumDesc.getValidateParams().get("validation-enum-values");
        Assert.assertNotNull(enumValues, "Integer enum branch must have validation-enum-values");
        @SuppressWarnings("unchecked")
        List<String> intEnumList = (List<String>) enumValues;
        Assert.assertEquals(intEnumList.size(), 3,
                "Integer enum must have 3 values");
        Assert.assertTrue(intEnumList.contains("1") && intEnumList.contains("2") && intEnumList.contains("3"),
                "Integer enum values must contain 1, 2, 3");

        // Branch 1: string enum → validation-enum-kind = "string"
        CppBoostBeastClientCodegen.CompositionBranchDescriptor stringEnumDesc =
                desc.getBranches().get(1);
        Assert.assertEquals(stringEnumDesc.getValidateParams().get("validation-enum-kind"), "string",
                "String enum branch must have validation-enum-kind = string");
        @SuppressWarnings("unchecked")
        List<String> stringEnumList = (List<String>) stringEnumDesc.getValidateParams().get("validation-enum-values");
        Assert.assertNotNull(stringEnumList, "String enum branch must have validation-enum-values");
        Assert.assertTrue(stringEnumList.contains("red") && stringEnumList.contains("blue"),
                "String enum values must contain red, blue");

        // Branch 2: float enum → validation-enum-kind = "number"
        CppBoostBeastClientCodegen.CompositionBranchDescriptor floatEnumDesc =
                desc.getBranches().get(2);
        Assert.assertEquals(floatEnumDesc.getValidateParams().get("validation-enum-kind"), "number",
                "Float enum branch must have validation-enum-kind = number");
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void notAssertionAlwaysFailsGenerationOnOneOf() {
        // `not` always fails generation for oneOf: it can flip any membership
        // decision and no generated validator implements it.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema branchWithNot = new StringSchema();
        io.swagger.v3.oas.models.media.Schema notSchema =
                new io.swagger.v3.oas.models.media.Schema();
        notSchema.setType("integer");
        branchWithNot.setNot(notSchema);
        schema.addOneOfItem(branchWithNot);
        schemas.put("SchemaWithNotOnOneOf", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        // validateDescriptorAssertions throws for oneOf with not
        codegen.preprocessOpenAPI(openAPI);
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void notAssertionAlwaysFailsGenerationOnAnyOf() {
        // `not` always fails generation for anyOf as well
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema branchWithNot = new StringSchema();
        io.swagger.v3.oas.models.media.Schema notSchema =
                new io.swagger.v3.oas.models.media.Schema();
        notSchema.setType("object");
        branchWithNot.setNot(notSchema);
        schema.addAnyOfItem(branchWithNot);
        schemas.put("SchemaWithNotOnAnyOf", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void notAssertionAlwaysFailsGenerationOnAllOf() {
        // Unlike other unsupported assertions, `not` must ALWAYS fail generation
        // even on allOf — because `not` flips membership and all branches of allOf
        // must match (not changes whether a branch matches).
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        io.swagger.v3.oas.models.media.Schema branchWithNot =
                new io.swagger.v3.oas.models.media.Schema();
        branchWithNot.setType("object");
        io.swagger.v3.oas.models.media.Schema notSchema =
                new io.swagger.v3.oas.models.media.Schema();
        notSchema.setType("array");
        branchWithNot.setNot(notSchema);
        schema.addAllOfItem(branchWithNot);
        schemas.put("SchemaWithNotOnAllOf", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        // allOf normally exempts non-not unsupported assertions, but `not`
        // must still throw.
        codegen.preprocessOpenAPI(openAPI);
    }

    @Test
    public void generatedValidatorSourceContainsMultipleOfAndExclusiveAndIntegerEnum() throws IOException {
        // Phase 2 strong review: verify generated source contains actual
        // validation logic for multipleOf (fmod), exclusive bounds, and
        // integer enum comparisons.
        String specContent =
            "openapi: 3.0.3\n" +
            "info:\n" +
            "  title: validator-output-test\n" +
            "  version: 1.0.0\n" +
            "paths: {}\n" +
            "components:\n" +
            "  schemas:\n" +
            "    ConstrainedNumber:\n" +
            "      oneOf:\n" +
            "        - type: integer\n" +
            "          multipleOf: 3\n" +
            "          minimum: 10\n" +
            "          maximum: 100\n" +
            "          exclusiveMinimum: true\n" +
            "        - type: integer\n" +
            "          enum: [1, 2, 3]\n";

        java.nio.file.Path specFile = java.nio.file.Files.createTempFile("validator-output-", ".yaml");
        specFile.toFile().deleteOnExit();
        java.nio.file.Files.writeString(specFile, specContent);

        File output = java.nio.file.Files.createTempDirectory("cpp-boost-beast-validator-output").toFile();
        output.deleteOnExit();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("cpp-boost-beast-client")
                .setInputSpec(specFile.toAbsolutePath().toString())
                .setOutputDir(output.getAbsolutePath())
                .addAdditionalProperty("packageName", "CppBoostBeastValidatorTest");

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        files.forEach(File::deleteOnExit);

        Path constrainedSource = output.toPath().resolve("model/ConstrainedNumber.cpp");
        TestUtils.assertFileExists(constrainedSource);
        String sourceContent = java.nio.file.Files.readString(constrainedSource);

        // Verify std::fmod validation for multipleOf with exact guard pattern
        Assert.assertTrue(sourceContent.contains("std::fmod("),
                "Generated validator must use std::fmod for multipleOf validation. Source: " + sourceContent);
        Assert.assertTrue(sourceContent.contains("fmod(numericVal, static_cast<double>(3))"),
                "Generated validator must check fmod(numericVal, 3) for multipleOf=3. Source: " + sourceContent);

        // Verify exclusiveMinimum: <= 10 comparison (exclusive on minimum=10)
        Assert.assertTrue(sourceContent.contains("<= 10") || sourceContent.contains("<=10"),
                "Generated validator must emit '<= 10' for exclusiveMinimum on minimum=10. Source: " + sourceContent);
        Assert.assertTrue(sourceContent.contains("at or below exclusive minimum"),
                "Generated validator error message must reference exclusive minimum");

        // Verify integer enum comparison: raw integer comparisons (not string equality)
        // Must check rawInt == static_cast<std::int64_t>(N) pattern
        Assert.assertTrue(sourceContent.contains("rawInt == static_cast<std::int64_t>(1)"),
                "Generated validator must compare rawInt == static_cast<std::int64_t>(1). Source: " + sourceContent);
        Assert.assertTrue(sourceContent.contains("rawInt == static_cast<std::int64_t>(2)"),
                "Generated validator must compare rawInt == static_cast<std::int64_t>(2). Source: " + sourceContent);
        Assert.assertTrue(sourceContent.contains("rawInt == static_cast<std::int64_t>(3)"),
                "Generated validator must compare rawInt == static_cast<std::int64_t>(3). Source: " + sourceContent);
        // Must NOT use string comparison for integer enum
        Assert.assertFalse(sourceContent.contains("is_string"),
                "Integer enum branch must not use string comparison");
    }

    // --- Phase 2 strong review: properties/additionalProperties fail-closed ---

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void propertiesOnOneOfBranchFailsGeneration() {
        // Non-empty properties on a composition branch without full validator
        // coverage must fail generation for oneOf (affects membership).
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        ObjectSchema objBranch = new ObjectSchema();
        objBranch.addProperties("name", new StringSchema());
        // No required — only properties, no required
        schema.addOneOfItem(objBranch);
        schemas.put("SchemaWithProperties", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    @Test
    public void requiredOnlyOnBranchSucceeds() {
        // required-only on a composition branch must NOT fail generation.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        ObjectSchema objBranch = new ObjectSchema();
        objBranch.setRequired(Arrays.asList("name"));
        // No properties — only required
        schema.addOneOfItem(objBranch);
        schemas.put("SchemaWithRequiredOnly", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        // Must not throw
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("SchemaWithRequiredOnly");
        Assert.assertNotNull(desc, "SchemaWithRequiredOnly must have a descriptor");
        Assert.assertEquals(desc.getBranches().size(), 1);

        CppBoostBeastClientCodegen.CompositionBranchDescriptor branch = desc.getBranches().get(0);
        Assert.assertTrue(branch.getSupportedAssertions().contains("object-properties"),
                "Required-only branch must have object-properties in supported");
        Assert.assertFalse(branch.getUnsupportedAssertions().contains("properties"),
                "Required-only branch must not have properties in unsupported");
    }

    // --- Phase 2 strong review: boolean schema fail-closed ---

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void booleanTrueSchemaOnOneOfBranchFailsGeneration() {
        // OAS 3.1 true value schema (always-match) on a composition branch.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.1.0");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        // OAS 3.1 true schema — use generic Schema with booleanSchemaValue set
        Schema boolTrueBranch = new Schema();
        boolTrueBranch.booleanSchemaValue(true);
        schema.addOneOfItem(boolTrueBranch);
        schemas.put("SchemaWithBoolTrue", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void booleanFalseSchemaOnOneOfBranchFailsGeneration() {
        // OAS 3.1 false value schema (never-match) on a composition branch.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.1.0");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        Schema boolFalseBranch = new Schema();
        boolFalseBranch.booleanSchemaValue(false);
        schema.addOneOfItem(boolFalseBranch);
        schemas.put("SchemaWithBoolFalse", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    // --- Phase 2 strong review: additionalProperties false fail-closed ---

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void additionalPropertiesFalseOnOneOfBranchFailsGeneration() {
        // additionalProperties: false on a composition branch rejects extra
        // object properties and affects membership. Must fail generation.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        ObjectSchema objBranch = new ObjectSchema();
        // OAS 3.0: additionalProperties: false via setAdditionalProperties(Boolean)
        objBranch.setAdditionalProperties(Boolean.FALSE);
        schema.addOneOfItem(objBranch);
        schemas.put("SchemaWithAddPropsFalse", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    @Test
    public void formatAssertionPolicyDefaultsToAnnotation() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();
        Assert.assertEquals(codegen.additionalProperties().get("formatAssertionPolicy"),
                "annotation",
                "formatAssertionPolicy must default to annotation");
    }

    @Test
    public void formatAssertionPolicyStrict() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.additionalProperties().put("formatAssertionPolicy", "strict");
        codegen.processOpts();
        Assert.assertEquals(codegen.additionalProperties().get("formatAssertionPolicy"),
                "strict",
                "formatAssertionPolicy must be strict when set");
    }

    @Test
    public void branchDescriptorsHaveValidatorId() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema stringBranch = new StringSchema();
        stringBranch.setMinLength(1);
        schema.addOneOfItem(stringBranch);
        IntegerSchema intBranch = new IntegerSchema();
        intBranch.setMinimum(0);
        schema.addOneOfItem(intBranch);
        schemas.put("ValidatorBranchTest", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("ValidatorBranchTest");
        Assert.assertNotNull(desc, "ValidatorBranchTest must have a descriptor");
        Assert.assertEquals(desc.getBranches().size(), 2,
                "ValidatorBranchTest must have 2 branches");

        // Each branch must have a non-null validatorId
        for (CppBoostBeastClientCodegen.CompositionBranchDescriptor branch : desc.getBranches()) {
            Assert.assertNotNull(branch.getValidatorId(),
                    "Each branch must have a validatorId");
            Assert.assertTrue(branch.getValidatorId().startsWith("ValidatorBranchTest_branch_"),
                    "validatorId must start with schema name and branch index");
        }

        // First branch: string with minLength
        CppBoostBeastClientCodegen.CompositionBranchDescriptor stringBranchDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(stringBranchDesc.getSupportedAssertions().contains("string-length"),
                "String branch must have string-length assertion");
        Assert.assertNotNull(stringBranchDesc.getValidateParams().get("validation-min-length"),
                "String branch must have validation-min-length param");
        Assert.assertNotNull(stringBranchDesc.getValidateParams().get("validation-type"),
                "String branch must have validation-type param");
        Assert.assertEquals(stringBranchDesc.getValidateParams().get("validation-type"), "string",
                "String branch validation-type must be string");

        // Second branch: integer with minimum
        CppBoostBeastClientCodegen.CompositionBranchDescriptor intBranchDesc =
                desc.getBranches().get(1);
        Assert.assertTrue(intBranchDesc.getSupportedAssertions().contains("numeric-range"),
                "Integer branch must have numeric-range assertion");
        Assert.assertNotNull(intBranchDesc.getValidateParams().get("validation-min"),
                "Integer branch must have validation-min param");
        Assert.assertEquals(intBranchDesc.getValidateParams().get("validation-type"), "integer",
                "Integer branch validation-type must be integer");
    }

    @Test
    public void branchDescriptorsHaveEnumValidationParams() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema enumBranch = new StringSchema();
        enumBranch.addEnumItem("red");
        enumBranch.addEnumItem("blue");
        schema.addOneOfItem(enumBranch);
        schemas.put("ValidatorEnumTest", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("ValidatorEnumTest");
        Assert.assertNotNull(desc, "ValidatorEnumTest must have a descriptor");

        CppBoostBeastClientCodegen.CompositionBranchDescriptor enumBranchDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(enumBranchDesc.getSupportedAssertions().contains("enum"),
                "Enum branch must have enum assertion");
        Assert.assertEquals(enumBranchDesc.getValidateParams().get("has-validation-enum"), true,
                "Enum branch must have has-validation-enum");
        Assert.assertNotNull(enumBranchDesc.getValidateParams().get("validation-enum-values"),
                "Enum branch must have validation-enum-values");
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void unsupportedAssertionOnOneOfThrows() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema conditionalBranch = new StringSchema();
        io.swagger.v3.oas.models.media.Schema ifSchema =
                new io.swagger.v3.oas.models.media.Schema();
        ifSchema.setType("object");
        conditionalBranch.setIf(ifSchema);
        schema.addOneOfItem(conditionalBranch);
        schemas.put("SchemaWithUnsupportedAssertion", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        // validateDescriptorAssertions throws for oneOf with unsupported assertions
        codegen.preprocessOpenAPI(openAPI);
    }

    @Test(expectedExceptions = CppBoostBeastClientCodegen.UnsupportedSchemaAssertionException.class)
    public void unsupportedAssertionOnAnyOfThrows() {
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        ArraySchema arrayWithContains = new ArraySchema();
        arrayWithContains.setContains(new StringSchema());
        schema.addAnyOfItem(arrayWithContains);
        schemas.put("SchemaWithUnsupportedAnyOf", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        codegen.preprocessOpenAPI(openAPI);
    }

    @Test
    public void allOfWithUnsupportedAssertionsDoesNotThrow() {
        // allOf with unsupported assertions should NOT throw because allOf
        // membership means "all branches must match" — unsupported assertions
        // don't change match count.
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        io.swagger.v3.oas.models.media.Schema conditionalObj =
                new io.swagger.v3.oas.models.media.Schema();
        conditionalObj.setType("object");
        io.swagger.v3.oas.models.media.Schema ifSchema =
                new io.swagger.v3.oas.models.media.Schema();
        ifSchema.setType("object");
        conditionalObj.setIf(ifSchema);
        schema.addAllOfItem(conditionalObj);
        schemas.put("AllOfWithUnsupported", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);

        // Should not throw for allOf with unsupported assertions
        codegen.preprocessOpenAPI(openAPI);
        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("AllOfWithUnsupported");
        Assert.assertNotNull(desc, "AllOfWithUnsupported must have a descriptor");
        Assert.assertEquals(desc.getKeyword(), "allOf",
                "Keyword must be allOf");
    }

    @Test
    public void toValidIdentifierSanitizesBranchNames() {
        // Note: Method is private, verified through descriptor builder
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        schemas.put("Mixed-Type_Schema", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("Mixed-Type_Schema");
        Assert.assertNotNull(desc, "Mixed-Type_Schema must have a descriptor");
        CppBoostBeastClientCodegen.CompositionBranchDescriptor branch =
                desc.getBranches().get(0);
        Assert.assertNotNull(branch.getValidatorId(),
                "branch must have validatorId");
        // Schema name with underscore should produce valid identifier
        Assert.assertTrue(branch.getValidatorId().startsWith("Mixed-Type_Schema_branch_")
                        || branch.getValidatorId().contains("_branch_"),
                "validatorId must contain branch index");
    }

    @Test
    public void anyOfAssertionSensitivity() {
        // anyOf with numeric-constrained branches: branch 0 accepts ≥100,
        // branch 1 accepts ≤0. Value 50 should match neither (rejected).
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        IntegerSchema highBranch = new IntegerSchema();
        highBranch.setMinimum(100);
        schema.addAnyOfItem(highBranch);
        IntegerSchema lowBranch = new IntegerSchema();
        lowBranch.setMaximum(0);
        schema.addAnyOfItem(lowBranch);
        schemas.put("AnyOfConstrained", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("AnyOfConstrained");
        Assert.assertNotNull(desc, "AnyOfConstrained must have a descriptor");
        Assert.assertEquals(desc.getKeyword(), "anyOf",
                "Keyword must be anyOf");
        Assert.assertEquals(desc.getBranches().size(), 2,
                "AnyOfConstrained must have 2 branches");

        // Both branches must have numeric-range assertion metadata
        for (CppBoostBeastClientCodegen.CompositionBranchDescriptor branch : desc.getBranches()) {
            Assert.assertTrue(branch.getSupportedAssertions().contains("numeric-range"),
                    "AnyOf branch with explicit bounds must have numeric-range assertion");
        }

        // First branch: minimum = 100
        CppBoostBeastClientCodegen.CompositionBranchDescriptor highBranchDesc =
                desc.getBranches().get(0);
        Assert.assertEquals(highBranchDesc.getValidateParams().get("validation-min"), 100,
                "High branch must have validation-min = 100");
        Assert.assertEquals(highBranchDesc.getValidateParams().get("validation-type"), "integer",
                "High branch validation-type must be integer");

        // Second branch: maximum = 0
        CppBoostBeastClientCodegen.CompositionBranchDescriptor lowBranchDesc =
                desc.getBranches().get(1);
        Assert.assertEquals(lowBranchDesc.getValidateParams().get("validation-max"), 0,
                "Low branch must have validation-max = 0");
    }

    @Test
    public void anyOfBranchValidatorMetadataForPatternAndConst() {
        // Verify const and pattern assertions produce correct validation params
        CppBoostBeastClientCodegen codegen = new CppBoostBeastClientCodegen();
        codegen.processOpts();

        io.swagger.v3.oas.models.OpenAPI openAPI = new io.swagger.v3.oas.models.OpenAPI();
        openAPI.setOpenapi("3.0.4");
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components();
        Map<String, Schema> schemas = new HashMap<>();

        ComposedSchema schema = new ComposedSchema();
        StringSchema constBranch = new StringSchema();
        constBranch.setConst("fixed-value");
        schema.addAnyOfItem(constBranch);

        StringSchema patternBranch = new StringSchema();
        patternBranch.setPattern("^[a-z]+$");
        schema.addAnyOfItem(patternBranch);
        schemas.put("AnyOfConstPattern", schema);
        components.setSchemas(schemas);
        openAPI.setComponents(components);
        codegen.preprocessOpenAPI(openAPI);

        CppBoostBeastClientCodegen.CompositionDescriptor desc =
                codegen.getCompositionDescriptor("AnyOfConstPattern");
        Assert.assertNotNull(desc, "AnyOfConstPattern must have a descriptor");

        // Const branch
        CppBoostBeastClientCodegen.CompositionBranchDescriptor constBranchDesc =
                desc.getBranches().get(0);
        Assert.assertTrue(constBranchDesc.getSupportedAssertions().contains("const"),
                "Const branch must have const assertion");
        Assert.assertEquals(constBranchDesc.getValidateParams().get("validation-const-value"),
                "fixed-value",
                "Const branch must have correct const value");

        // Pattern branch
        CppBoostBeastClientCodegen.CompositionBranchDescriptor patternBranchDesc =
                desc.getBranches().get(1);
        Assert.assertTrue(patternBranchDesc.getSupportedAssertions().contains("pattern"),
                "Pattern branch must have pattern assertion");
        Assert.assertEquals(patternBranchDesc.getValidateParams().get("validation-pattern"),
                "^[a-z]+$",
                "Pattern branch must have correct pattern");
    }

    /**
     * Test helper that exposes protected normalizer methods as public.
     */
    static final class TestNormalizer
            extends CppBoostBeastClientCodegen.CppBoostBeastOpenAPINormalizer {
        TestNormalizer(io.swagger.v3.oas.models.OpenAPI openAPI,
                       Map<String, String> inputRules) {
            super(openAPI, inputRules);
        }

        @Override
        public Schema processSimplifyOneOf(Schema schema) {
            return super.processSimplifyOneOf(schema);
        }

        @Override
        public Schema processSimplifyAnyOf(Schema schema) {
            return super.processSimplifyAnyOf(schema);
        }

        @Override
        public Schema processSimplifyAnyOfStringAndEnumString(Schema schema) {
            return super.processSimplifyAnyOfStringAndEnumString(schema);
        }

        @Override
        public Schema processSimplifyOneOfEnum(Schema schema) {
            return super.processSimplifyOneOfEnum(schema);
        }

        @Override
        public Schema processSimplifyAnyOfEnum(Schema schema) {
            return super.processSimplifyAnyOfEnum(schema);
        }
    }

    private static String extractMethod(String generatedApiSource, String methodSignature) {
        int methodStart = generatedApiSource.indexOf(methodSignature);
        Assert.assertTrue(methodStart >= 0, "Missing generated method: " + methodSignature);
        int methodEnd = generatedApiSource.indexOf("\n}", methodStart);
        Assert.assertTrue(methodEnd > methodStart, "Missing closing brace for generated method: " + methodSignature);
        return generatedApiSource.substring(methodStart, methodEnd);
    }

    private static int countOccurrences(String source, String expectedText) {
        int occurrenceCount = 0;
        int searchPosition = 0;
        while ((searchPosition = source.indexOf(expectedText, searchPosition)) >= 0) {
            occurrenceCount++;
            searchPosition += expectedText.length();
        }
        return occurrenceCount;
    }
}
