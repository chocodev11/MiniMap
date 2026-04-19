ivec4 _7a81e42fddee2f93(vec4 _e30c3475a3a85ac1){
    return ivec4(round(_e30c3475a3a85ac1 * 255));
}

int _27e4a854910d5a3f(vec4 _e30c3475a3a85ac1){
    ivec4 _8191e12dd310f85c = _7a81e42fddee2f93(_e30c3475a3a85ac1);
    return _8191e12dd310f85c.r << 16 | _8191e12dd310f85c.g << 8 | _8191e12dd310f85c.b;
}

vec4 _e72020d0026b8201(ivec4 _e30c3475a3a85ac1){
    return vec4(_e30c3475a3a85ac1) / 255;
}
