/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.dictionary;

import org.agrona.generation.PackageOutputManager;
import uk.co.real_logic.artio.builder.RejectUnknownField;
import uk.co.real_logic.artio.builder.Validation;
import uk.co.real_logic.artio.dictionary.generation.*;
import uk.co.real_logic.artio.dictionary.ir.Component;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.Field;
import uk.co.real_logic.artio.dictionary.ir.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.*;

public final class CodecGenerationTool
{

    public static void main(final String[] args) throws Exception
    {
        if (args.length < 2)
        {
            printUsageAndExit();
        }

        final String outputPath = args[0];
        final String files = args[1];
        final String[] fileNames = files.split(";");
        if (fileNames.length > 2)
        {
            System.err.print("Two many dictionary files(1 or 2 dictionaries supported)." + files);
            printUsageAndExit();
        }
        final DictionaryParser dictionaryParser = new DictionaryParser();
        final Dictionary dictionary;

        if (fileNames.length == 1)
        {
            dictionary = parseDictionary(fileNames[0], dictionaryParser::parseWithHeaders);
        }
        else
        {
            final Dictionary fixtDictionary = parseDictionary(fileNames[0], dictionaryParser::parseWithHeaders);
            final Dictionary fixDictionary = parseDictionary(fileNames[1], dictionaryParser::parse);
            dictionary = mergeDictionaries(fixtDictionary, fixDictionary);
        }

        final PackageOutputManager parent = new PackageOutputManager(outputPath, PARENT_PACKAGE);
        final PackageOutputManager decoder = new PackageOutputManager(outputPath, DECODER_PACKAGE);

        final EnumGenerator enumGenerator = new EnumGenerator(dictionary, PARENT_PACKAGE, parent);
        final ConstantGenerator constantGenerator = new ConstantGenerator(dictionary, PARENT_PACKAGE, parent);

        final EncoderGenerator encoderGenerator = new EncoderGenerator(
            dictionary,
            1,
            ENCODER_PACKAGE,
            PARENT_PACKAGE,
            new PackageOutputManager(outputPath, ENCODER_PACKAGE), Validation.class, RejectUnknownField.class);

        final DecoderGenerator decoderGenerator = new DecoderGenerator(
            dictionary, 1, DECODER_PACKAGE, PARENT_PACKAGE, decoder, Validation.class, RejectUnknownField.class);
        final PrinterGenerator printerGenerator = new PrinterGenerator(dictionary, DECODER_PACKAGE, decoder);
        final AcceptorGenerator acceptorGenerator = new AcceptorGenerator(dictionary, DECODER_PACKAGE, decoder);

        enumGenerator.generate();
        constantGenerator.generate();

        encoderGenerator.generate();

        decoderGenerator.generate();
        printerGenerator.generate();
        acceptorGenerator.generate();
    }

    private static Dictionary mergeDictionaries(final Dictionary fixtDictionary, final Dictionary fixDictionary)
    {
        final List<Message> allMessages = new ArrayList<>(fixtDictionary.messages());
        allMessages.addAll(fixDictionary.messages());
        final Map<String, Field> allFields = new HashMap<>(fixtDictionary.fields());
        allFields.putAll(fixDictionary.fields());
        final Map<String, Component> allComponents = new HashMap<>(fixtDictionary.components());
        allComponents.putAll(fixDictionary.components());

        return new Dictionary(allMessages, allFields, allComponents,
                fixtDictionary.header(), fixtDictionary.trailer(),
                fixtDictionary.specType(), fixtDictionary.majorVersion(), fixtDictionary.minorVersion());
    }

    private static Dictionary parseDictionary(final String fileName, final DictionaryMapper parser) throws Exception
    {
        final File xmlFile = new File(fileName);
        if (!xmlFile.exists())
        {
            System.err.println("xmlFile does not exist: " + xmlFile.getAbsolutePath());
            printUsageAndExit();
        }

        if (!xmlFile.isFile())
        {
            System.out.println("xmlFile isn't a file, are the arguments the correct way around?");
            printUsageAndExit();
        }
        try (FileInputStream input = new FileInputStream(xmlFile))
        {
            return parser.parse(input);
        }
    }

    interface DictionaryMapper
    {
        Dictionary parse(InputStream inputStream) throws Exception;
    }

    private static void printUsageAndExit()
    {
        System.err.println("Usage: CodecGenerationTool </path/to/output-directory> " +
            "<[/path/to/fixt-xml/dictionary;]/path/to/xml/dictionary>");
        System.exit(-1);
    }
}
