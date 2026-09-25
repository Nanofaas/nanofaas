package nanofaas

import (
	"bytes"
	"encoding"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net"
	"net/netip"
	"reflect"
	"strings"
	"time"
	"unicode/utf8"
)

var (
	errPayloadTooLarge      = errors.New("serialized payload exceeds limit")
	errJSONSerialization    = errors.New("JSON serialization failed")
	errCustomJSONMarshaler  = errors.New("custom JSON marshaler is not supported by bounded encoding")
	errJSONTraversalTooDeep = errors.New("JSON value exceeds bounded traversal depth")
	jsonMarshalerType       = reflect.TypeFor[json.Marshaler]()
	textMarshalerType       = reflect.TypeFor[encoding.TextMarshaler]()
	rawMessageType          = reflect.TypeFor[json.RawMessage]()
	jsonNumberType          = reflect.TypeFor[json.Number]()
	timeType                = reflect.TypeFor[time.Time]()
	netIPType               = reflect.TypeFor[net.IP]()
	netipAddrType           = reflect.TypeFor[netip.Addr]()
	netipAddrPortType       = reflect.TypeFor[netip.AddrPort]()
	netipPrefixType         = reflect.TypeFor[netip.Prefix]()
)

const maxJSONPreflightDepth = 100

type limitedBuffer struct {
	buffer bytes.Buffer
	limit  int64
}

func (w *limitedBuffer) Write(body []byte) (int, error) {
	remaining := w.limit - int64(w.buffer.Len())
	if int64(len(body)) > remaining {
		if remaining > 0 {
			_, _ = w.buffer.Write(body[:remaining])
		}
		return 0, errPayloadTooLarge
	}
	return w.buffer.Write(body)
}

func encodeJSONBounded(value any, limit int64) ([]byte, error) {
	preflight := jsonPreflight{limit: limit, visiting: make(map[jsonVisit]struct{})}
	if err := preflight.value(reflect.ValueOf(value), 0); err != nil {
		return nil, err
	}
	if err := preflight.add(1); err != nil {
		return nil, err
	}

	// The preflight upper-bounds every accepted stdlib encoding path. Custom
	// marshalers are rejected before invocation, so Encoder's internal buffer
	// cannot grow beyond limit.
	writer := &limitedBuffer{limit: limit}
	if err := json.NewEncoder(writer).Encode(value); err != nil {
		return nil, err
	}
	return writer.buffer.Bytes(), nil
}

type jsonVisit struct {
	typ reflect.Type
	ptr uintptr
}

type jsonPreflight struct {
	limit    int64
	size     int64
	visiting map[jsonVisit]struct{}
}

func (p *jsonPreflight) add(size int64) error {
	if size < 0 || p.limit < 0 || size > p.limit-p.size {
		return errPayloadTooLarge
	}
	p.size += size
	return nil
}

func (p *jsonPreflight) value(value reflect.Value, depth int) error {
	if depth > maxJSONPreflightDepth {
		return errJSONTraversalTooDeep
	}
	if !value.IsValid() {
		return p.add(4)
	}
	if known, err := p.knownBoundedEncoding(value); known {
		return err
	}
	if value.Type() == jsonNumberType {
		return p.add(int64(len(value.String())))
	}
	if hasCustomJSONEncoding(value.Type()) {
		return fmt.Errorf("%w: %s", errCustomJSONMarshaler, value.Type())
	}
	if isNilJSONValue(value) {
		return p.add(4)
	}
	return p.kind(value, depth)
}

func (p *jsonPreflight) kind(value reflect.Value, depth int) error {
	switch value.Kind() {
	case reflect.Interface:
		return p.value(value.Elem(), depth+1)
	case reflect.Pointer:
		return p.withVisit(value, func() error { return p.value(value.Elem(), depth+1) })
	case reflect.Bool:
		return p.add(5)
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return p.add(20)
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return p.add(20)
	case reflect.Float32:
		return p.float(value, 16)
	case reflect.Float64:
		return p.float(value, 24)
	case reflect.String:
		return p.quotedString(value.String())
	case reflect.Slice:
		if value.Type().Elem().Kind() == reflect.Uint8 {
			return p.byteSlice(value)
		}
		return p.withVisit(value, func() error { return p.sequence(value, depth) })
	case reflect.Array:
		return p.sequence(value, depth)
	case reflect.Map:
		return p.withVisit(value, func() error { return p.objectMap(value, depth) })
	case reflect.Struct:
		return p.objectStruct(value, depth)
	default:
		return &json.UnsupportedTypeError{Type: value.Type()}
	}
}

func (p *jsonPreflight) float(value reflect.Value, size int64) error {
	if math.IsNaN(value.Float()) || math.IsInf(value.Float(), 0) {
		return &json.UnsupportedValueError{Value: value, Str: "non-finite float"}
	}
	return p.add(size)
}

// byteSlice sizes a []byte, which encoding/json writes as a quoted base64 string.
func (p *jsonPreflight) byteSlice(value reflect.Value) error {
	if int64(value.Len()) > p.limit-p.size {
		return errPayloadTooLarge
	}
	encoded := int64(base64.StdEncoding.EncodedLen(value.Len()))
	return p.add(encoded + 2)
}

func (p *jsonPreflight) sequence(value reflect.Value, depth int) error {
	if err := p.add(2); err != nil {
		return err
	}
	for index := 0; index < value.Len(); index++ {
		if index > 0 {
			if err := p.add(1); err != nil {
				return err
			}
		}
		if err := p.value(value.Index(index), depth+1); err != nil {
			return err
		}
	}
	return nil
}

func (p *jsonPreflight) objectMap(value reflect.Value, depth int) error {
	if err := checkJSONMapKeyType(value.Type()); err != nil {
		return err
	}
	if err := p.add(2); err != nil {
		return err
	}
	iterator := value.MapRange()
	for index := 0; iterator.Next(); index++ {
		if err := p.mapEntry(iterator.Key(), iterator.Value(), index, depth); err != nil {
			return err
		}
	}
	return nil
}

func checkJSONMapKeyType(mapType reflect.Type) error {
	keyType := mapType.Key()
	knownKey := isKnownBoundedEncodingType(keyType)
	if keyType.Kind() != reflect.String && keyType.Implements(textMarshalerType) && !knownKey {
		return fmt.Errorf("%w: map key %s", errCustomJSONMarshaler, keyType)
	}
	if !isBuiltinJSONMapKey(keyType) && !knownKey {
		return &json.UnsupportedTypeError{Type: mapType}
	}
	return nil
}

// mapEntry sizes one "key":value pair, preceded by a comma after the first entry.
func (p *jsonPreflight) mapEntry(key, item reflect.Value, index, depth int) error {
	if index > 0 {
		if err := p.add(1); err != nil {
			return err
		}
	}
	if err := p.mapKey(key); err != nil {
		return err
	}
	if err := p.add(1); err != nil {
		return err
	}
	return p.value(item, depth+1)
}

func (p *jsonPreflight) objectStruct(value reflect.Value, depth int) error {
	if err := p.add(2); err != nil {
		return err
	}
	fieldCount := 0
	typ := value.Type()
	for index := 0; index < value.NumField(); index++ {
		field := typ.Field(index)
		tagName, options, _ := strings.Cut(field.Tag.Get("json"), ",")
		if tagName == "-" || (field.PkgPath != "" && !field.Anonymous) {
			continue
		}
		if err := p.structField(field.Name, tagName, options, value.Field(index), fieldCount, depth); err != nil {
			return err
		}
		fieldCount++
	}
	return nil
}

// structField sizes one encoded field, using the longer of its Go and tag names as the key.
func (p *jsonPreflight) structField(fieldName, tagName, options string, field reflect.Value,
	fieldCount, depth int) error {
	if fieldCount > 0 {
		if err := p.add(1); err != nil {
			return err
		}
	}
	name := fieldName
	if escapedJSONStringSize(tagName) > escapedJSONStringSize(name) {
		name = tagName
	}
	if err := p.quotedString(name); err != nil {
		return err
	}
	if err := p.add(1); err != nil {
		return err
	}
	beforeValue := p.size
	if err := p.value(field, depth+1); err != nil {
		return err
	}
	if hasJSONTagOption(options, "string") && isJSONStringOptionValue(field) {
		return p.add((p.size - beforeValue) + 2)
	}
	return nil
}

func (p *jsonPreflight) mapKey(key reflect.Value) error {
	if known, err := p.knownBoundedEncoding(key); known {
		return err
	}
	switch key.Kind() {
	case reflect.String:
		return p.quotedString(key.String())
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return p.add(22)
	default:
		return &json.UnsupportedTypeError{Type: key.Type()}
	}
}

func (p *jsonPreflight) quotedString(value string) error {
	return p.add(escapedJSONStringSize(value))
}

func (p *jsonPreflight) rawMessage(body []byte) error {
	if body == nil {
		return p.add(4)
	}
	if len(body) == 0 {
		return p.add(1)
	}
	remaining := p.limit - p.size
	if int64(len(body)) > remaining/6 {
		return errPayloadTooLarge
	}
	return p.add(int64(len(body)) * 6)
}

func (p *jsonPreflight) withVisit(value reflect.Value, visitValue func() error) error {
	ptr := value.Pointer()
	if ptr == 0 {
		return p.add(4)
	}
	visit := jsonVisit{typ: value.Type(), ptr: ptr}
	if _, exists := p.visiting[visit]; exists {
		return &json.UnsupportedValueError{Value: value, Str: "encountered a cycle"}
	}
	p.visiting[visit] = struct{}{}
	defer delete(p.visiting, visit)
	return visitValue()
}

func isNilJSONValue(value reflect.Value) bool {
	switch value.Kind() {
	case reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return value.IsNil()
	default:
		return false
	}
}

func hasCustomJSONEncoding(typ reflect.Type) bool {
	if typ.Implements(jsonMarshalerType) || typ.Implements(textMarshalerType) {
		return true
	}
	if typ.Kind() == reflect.Pointer {
		return false
	}
	pointerType := reflect.PointerTo(typ)
	return pointerType.Implements(jsonMarshalerType) || pointerType.Implements(textMarshalerType)
}

func (p *jsonPreflight) knownBoundedEncoding(value reflect.Value) (bool, error) {
	typ := value.Type()
	if typ.Kind() == reflect.Pointer && value.IsNil() && isKnownBoundedEncodingType(typ) {
		return true, p.add(4)
	}
	switch typ {
	case rawMessageType:
		return true, p.rawMessage(value.Bytes())
	case reflect.PointerTo(rawMessageType):
		return true, p.rawMessage(value.Elem().Bytes())
	case timeType, reflect.PointerTo(timeType):
		// time.Time.MarshalJSON allocates at most RFC3339Nano plus quotes.
		return true, p.add(64)
	case netIPType:
		return true, p.netIP(value.Bytes())
	case reflect.PointerTo(netIPType):
		return true, p.netIP(value.Elem().Bytes())
	case netipAddrType:
		return true, p.netipText(39, value.Interface().(netip.Addr).Zone())
	case reflect.PointerTo(netipAddrType):
		return true, p.netipText(39, value.Elem().Interface().(netip.Addr).Zone())
	case netipAddrPortType:
		return true, p.netipText(47, value.Interface().(netip.AddrPort).Addr().Zone())
	case reflect.PointerTo(netipAddrPortType):
		return true, p.netipText(47, value.Elem().Interface().(netip.AddrPort).Addr().Zone())
	case netipPrefixType:
		return true, p.netipText(43, value.Interface().(netip.Prefix).Addr().Zone())
	case reflect.PointerTo(netipPrefixType):
		return true, p.netipText(43, value.Elem().Interface().(netip.Prefix).Addr().Zone())
	default:
		return false, nil
	}
}

func isKnownBoundedEncodingType(typ reflect.Type) bool {
	if typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	return typ == rawMessageType || typ == timeType || typ == netIPType || typ == netipAddrType ||
		typ == netipAddrPortType || typ == netipPrefixType
}

func (p *jsonPreflight) netIP(body []byte) error {
	switch len(body) {
	case 0:
		return p.add(2)
	case net.IPv4len, net.IPv6len:
		return p.add(41)
	default:
		return fmt.Errorf("invalid net.IP length %d", len(body))
	}
}

func (p *jsonPreflight) netipText(maximumWithoutZone int64, zone string) error {
	size := maximumWithoutZone + 2
	if zone != "" {
		size += 1 + escapedJSONStringSize(zone) - 2
	}
	return p.add(size)
}

func isBuiltinJSONMapKey(typ reflect.Type) bool {
	switch typ.Kind() {
	case reflect.String, reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return true
	default:
		return false
	}
}

func escapedJSONStringSize(value string) int64 {
	size := int64(2)
	for index := 0; index < len(value); {
		character := value[index]
		if character < utf8.RuneSelf {
			switch {
			case character == '"' || character == '\\' || character == '\b' || character == '\f' ||
				character == '\n' || character == '\r' || character == '\t':
				size += 2
			case character < 0x20 || character == '<' || character == '>' || character == '&':
				size += 6
			default:
				size++
			}
			index++
			continue
		}
		runeValue, runeBytes := utf8.DecodeRuneInString(value[index:])
		if runeValue == utf8.RuneError && runeBytes == 1 {
			size += 6
			index++
			continue
		}
		if runeValue == '\u2028' || runeValue == '\u2029' {
			size += 6
		} else {
			size += int64(runeBytes)
		}
		index += runeBytes
	}
	return size
}

func hasJSONTagOption(options, wanted string) bool {
	for options != "" {
		var option string
		option, options, _ = strings.Cut(options, ",")
		if option == wanted {
			return true
		}
	}
	return false
}

func isJSONStringOptionValue(value reflect.Value) bool {
	for value.IsValid() && (value.Kind() == reflect.Interface || value.Kind() == reflect.Pointer) {
		if value.IsNil() {
			return false
		}
		value = value.Elem()
	}
	if !value.IsValid() {
		return false
	}
	switch value.Kind() {
	case reflect.Bool, reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr,
		reflect.Float32, reflect.Float64, reflect.String:
		return true
	default:
		return false
	}
}
