function isJsonObject(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
function remapObject(data, fieldMap) {
    const output = {};
    for (const [key, value] of Object.entries(data)) {
        output[fieldMap[key] ?? key] = value;
    }
    return output;
}
export const handleJsonTransform = async (ctx, req) => {
    ctx.logger.info("processing json transform");
    if (!isJsonObject(req.input)) {
        return { error: "Input must be a JSON object" };
    }
    const data = req.input.data;
    if (!isJsonObject(data)) {
        return { error: "Field 'data' must be a JSON object" };
    }
    const rawFieldMap = req.input.fieldMap;
    const fieldMap = isJsonObject(rawFieldMap)
        ? Object.fromEntries(Object.entries(rawFieldMap)
            .filter((entry) => typeof entry[1] === "string"))
        : {};
    return remapObject(data, fieldMap);
};
