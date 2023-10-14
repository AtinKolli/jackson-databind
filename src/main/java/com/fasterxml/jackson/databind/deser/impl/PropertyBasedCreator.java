package com.fasterxml.jackson.databind.deser.impl;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;

/**
 * Object that is used to collect arguments for non-default creator
 * (non-default-constructor, or argument-taking factory method)
 * before creator can be called.
 * Since ordering of JSON properties is not guaranteed, this may
 * require buffering of values other than ones being passed to
 * creator.
 */
public final class PropertyBasedCreator
{
    /**
     * Number of properties: usually same as size of {@link #_propertyLookup},
     * but not necessarily, when we have unnamed injectable properties.
     */
    protected final int _propertyCount;

    /**
     * Helper object that knows how to actually construct the instance by
     * invoking creator method with buffered arguments.
     */
    protected final ValueInstantiator _valueInstantiator;

    /**
     * Map that contains property objects for either constructor or factory
     * method (whichever one is null: one property for each
     * parameter for that one), keyed by logical property name
     */
    protected final HashMap<String, SettableBeanProperty> _propertyLookup;

    /**
     * Array that contains properties that match creator properties
     */
    protected final SettableBeanProperty[] _propertiesInOrder;

    /*
    /**********************************************************
    /* Construction, initialization
    /**********************************************************
     */

    protected PropertyBasedCreator(DeserializationContext ctxt,
            ValueInstantiator valueInstantiator,
            SettableBeanProperty[] creatorProps,
            boolean caseInsensitive,
            boolean addAliases)
    {
        _valueInstantiator = valueInstantiator;
        if (caseInsensitive) {
            _propertyLookup = new CaseInsensitiveMap();
        } else {
            _propertyLookup = new HashMap<String, SettableBeanProperty>();
        }
        final int len = creatorProps.length;
        _propertyCount = len;

        // 26-Feb-2017, tatu: Let's start by aliases, so that there is no
        //    possibility of accidental override of primary names
        if (addAliases) {
            final DeserializationConfig config = ctxt.getConfig();
            for (SettableBeanProperty prop : creatorProps) {
                List<PropertyName> aliases = prop.findAliases(config);
                if (!aliases.isEmpty()) {
                    for (PropertyName pn : aliases) {
                        _propertyLookup.put(pn.getSimpleName(), prop);
                    }
                }
            }
        }
        _propertiesInOrder = new SettableBeanProperty[len];
        for (int i = 0; i < len; ++i) {
            SettableBeanProperty prop = creatorProps[i];
            _propertiesInOrder[i] = prop;
            _propertyLookup.put(prop.getName(), prop);
        }
    }

    /**
     * Factory method used for building actual instances to be used with POJOS:
     * resolves deserializers, checks for "null values".
     */
    public static PropertyBasedCreator construct(DeserializationContext ctxt,
            ValueInstantiator valueInstantiator, SettableBeanProperty[] srcCreatorProps,
            BeanPropertyMap allProperties)
        throws JsonMappingException
    {
        final int len = srcCreatorProps.length;
        SettableBeanProperty[] creatorProps = new SettableBeanProperty[len];
        for (int i = 0; i < len; ++i) {
            SettableBeanProperty prop = srcCreatorProps[i];
            if (!prop.hasValueDeserializer()) {
                prop = prop.withValueDeserializer(ctxt.findContextualValueDeserializer(prop.getType(), prop));
            }
            creatorProps[i] = prop;
        }
        return new PropertyBasedCreator(ctxt, valueInstantiator, creatorProps,
                allProperties.isCaseInsensitive(),
                allProperties.hasAliases());
    }

    /**
     * Factory method used for building actual instances to be used with types
     * OTHER than POJOs.
     * resolves deserializers and checks for "null values".
     */
    public static PropertyBasedCreator construct(DeserializationContext ctxt,
            ValueInstantiator valueInstantiator, SettableBeanProperty[] srcCreatorProps,
            boolean caseInsensitive)
        throws JsonMappingException
    {
        final int len = srcCreatorProps.length;
        SettableBeanProperty[] creatorProps = new SettableBeanProperty[len];
        for (int i = 0; i < len; ++i) {
            SettableBeanProperty prop = srcCreatorProps[i];
            if (!prop.hasValueDeserializer()) {
                prop = prop.withValueDeserializer(ctxt.findContextualValueDeserializer(prop.getType(), prop));
            }
            creatorProps[i] = prop;
        }
        return new PropertyBasedCreator(ctxt, valueInstantiator, creatorProps, 
                caseInsensitive, false);
    }

    /*
    /**********************************************************
    /* Accessors
    /**********************************************************
     */

    public Collection<SettableBeanProperty> properties() {
        return _propertyLookup.values();
    }

    public SettableBeanProperty findCreatorProperty(String name) {
        return _propertyLookup.get(name);
    }

    public SettableBeanProperty findCreatorProperty(int propertyIndex) {
        for (SettableBeanProperty prop : _propertyLookup.values()) {
            if (prop.getPropertyIndex() == propertyIndex) {
                return prop;
            }
        }
        return null;
    }

    /*
    /**********************************************************
    /* Building process
    /**********************************************************
     */

    /**
     * Method called when starting to build a bean instance.
     */
    public PropertyValueBuffer startBuilding(JsonParser p, DeserializationContext ctxt,
            ObjectIdReader oir) {
        return new PropertyValueBuffer(p, ctxt, _propertyCount, oir);
    }

    public Object build(DeserializationContext ctxt, PropertyValueBuffer buffer) throws IOException
    {
        Object bean = _valueInstantiator.createFromObjectWith(ctxt,
                _propertiesInOrder, buffer);
        // returning null isn't quite legal, but let's let caller deal with that
        if (bean != null) {
            // Object Id to handle?
            bean = buffer.handleIdValue(ctxt, bean);
            
            // Anything buffered?
            for (PropertyValue pv = buffer.buffered(); pv != null; pv = pv.next) {
                pv.assign(bean);
            }
        }
        return bean;
    }

    /*
    /**********************************************************
    /* Helper classes
    /**********************************************************
     */

    /**
     * Simple override of standard {@link java.util.HashMap} to support
     * case-insensitive access to creator properties
     *
     * @since 2.8.5
     */
    static class CaseInsensitiveMap extends HashMap<String, SettableBeanProperty>
    {
        private static final long serialVersionUID = 1L;

        @Override
        public SettableBeanProperty get(Object key0) {
            return super.get(((String) key0).toLowerCase());
        }

        @Override
        public SettableBeanProperty put(String key, SettableBeanProperty value) {
            key = key.toLowerCase();
            return super.put(key, value);
        }
    }
}
